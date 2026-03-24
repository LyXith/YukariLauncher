package com.arata.yukarilauncher.termux

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * TermuxBootstrapInstaller
 *
 * Robust bootstrap installer that auto-detects the zip structure.
 *
 * Termux bootstrap zips from packages.termux.dev can have two layouts:
 *
 *   Layout A (no prefix):   bin/bash, lib/libssl.so  → extract into prefixDir (usr/)
 *   Layout B (usr prefix):  usr/bin/bash, usr/lib/   → extract into bootstrapRootDir
 *
 * We peek at the first entries and decide automatically.
 *
 * Final layout on disk:
 *   filesDir/bootstrap/
 *     usr/bin/bash    ← $PREFIX/bin/bash
 *     usr/lib/
 *     home/           ← $HOME
 */
object TermuxBootstrapInstaller {

    private const val TAG = "BootstrapInstaller"

    // ── Directories ───────────────────────────────────────────────────────────

    fun bootstrapRootDir(ctx: Context) = File(ctx.filesDir, "bootstrap")
    fun prefixDir(ctx: Context)        = File(bootstrapRootDir(ctx), "usr")
    fun homeDir(ctx: Context)          = File(bootstrapRootDir(ctx), "home")

    fun isInstalled(ctx: Context): Boolean =
        File(prefixDir(ctx), "bin/bash").exists()

    // ── ABI ───────────────────────────────────────────────────────────────────

    private fun abi(): String = when {
        Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }    -> "aarch64"
        Build.SUPPORTED_ABIS.any { it == "armeabi-v7a" }  -> "arm"
        Build.SUPPORTED_ABIS.any { it == "x86_64" }       -> "x86_64"
        Build.SUPPORTED_ABIS.any { it.startsWith("x86") } -> "i686"
        else -> "aarch64"
    }

    private fun bootstrapUrl(): String =
        "https://packages.termux.dev/bootstrap/bootstrap-${abi()}.zip"

    // ── Progress ──────────────────────────────────────────────────────────────

    enum class Stage { CHECKING, DOWNLOADING, EXTRACTING, SYMLINKS, DONE, ERROR }
    data class Progress(val stage: Stage, val percent: Int = 0, val error: Throwable? = null)

    // ── Install ───────────────────────────────────────────────────────────────

    fun install(ctx: Context, onProgress: (Progress) -> Unit) {
        try {
            onProgress(Progress(Stage.CHECKING))

            if (isInstalled(ctx)) {
                Log.i(TAG, "Already installed at ${prefixDir(ctx)}")
                onProgress(Progress(Stage.DONE))
                return
            }

            // Clean partial installs
            bootstrapRootDir(ctx).deleteRecursively()
            bootstrapRootDir(ctx).mkdirs()

            val zipFile = File(ctx.cacheDir, "bootstrap-${abi()}.zip")

            // Download
            downloadZip(bootstrapUrl(), zipFile) { pct ->
                onProgress(Progress(Stage.DOWNLOADING, pct))
            }
            Log.i(TAG, "Zip downloaded: ${zipFile.length()} bytes at ${zipFile.absolutePath}")

            // Peek at zip to determine structure
            onProgress(Progress(Stage.EXTRACTING))
            val structure = detectZipStructure(zipFile)
            Log.i(TAG, "Zip structure detected: $structure")

            // Choose extraction root based on structure
            val extractRoot = when (structure) {
                ZipStructure.HAS_USR_PREFIX  -> bootstrapRootDir(ctx)  // zip has usr/bin/bash
                ZipStructure.NO_PREFIX       -> prefixDir(ctx)          // zip has bin/bash
                ZipStructure.HAS_DATA_PREFIX -> bootstrapRootDir(ctx)   // zip has data/.../usr/
            }
            extractRoot.mkdirs()
            Log.i(TAG, "Extracting into: ${extractRoot.absolutePath}")

            val symlinkLines = mutableListOf<String>()
            val fileCount = extractZip(zipFile, extractRoot, structure, symlinkLines)
            Log.i(TAG, "Extracted $fileCount files")
            zipFile.delete()

            // Verify bash exists after extraction
            val bash = File(prefixDir(ctx), "bin/bash")
            Log.i(TAG, "bash exists after extract: ${bash.exists()} at ${bash.absolutePath}")

            if (!bash.exists()) {
                // Log what we actually got
                Log.e(TAG, "bash NOT found! Contents of bootstrapRootDir:")
                bootstrapRootDir(ctx).walkTopDown().take(30).forEach {
                    Log.e(TAG, "  ${it.absolutePath}")
                }
                throw Exception("bash not found after extraction. Check logs for zip contents.")
            }

            // Symlinks
            onProgress(Progress(Stage.SYMLINKS))
            createSymlinks(bootstrapRootDir(ctx), symlinkLines)

            // chmod
            makeBinariesExecutable(prefixDir(ctx))

            // Ensure dirs
            homeDir(ctx).mkdirs()
            File(prefixDir(ctx), "tmp").mkdirs()

            onProgress(Progress(Stage.DONE))
            Log.i(TAG, "Bootstrap install complete.")

        } catch (e: Exception) {
            Log.e(TAG, "Bootstrap install FAILED: ${e.message}", e)
            onProgress(Progress(Stage.ERROR, error = e))
        }
    }

    // ── Zip structure detection ───────────────────────────────────────────────

    private enum class ZipStructure {
        HAS_USR_PREFIX,   // entries like usr/bin/bash, home/
        NO_PREFIX,        // entries like bin/bash, lib/
        HAS_DATA_PREFIX   // entries like data/data/com.termux/files/usr/...
    }

    private fun detectZipStructure(zipFile: File): ZipStructure {
        val entries = mutableListOf<String>()
        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            var count = 0
            while (entry != null && count < 20) {
                if (entry.name != "SYMLINKS.txt") {
                    entries.add(entry.name)
                    count++
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        Log.i(TAG, "First zip entries: ${entries.take(10)}")

        return when {
            entries.any { it.startsWith("data/") }       -> ZipStructure.HAS_DATA_PREFIX
            entries.any { it.startsWith("usr/") || it.startsWith("home/") } -> ZipStructure.HAS_USR_PREFIX
            entries.any { it.startsWith("bin/") || it.startsWith("lib/") || it.startsWith("etc/") } -> ZipStructure.NO_PREFIX
            else -> ZipStructure.NO_PREFIX  // safe default
        }
    }

    // ── Extract ───────────────────────────────────────────────────────────────

    private fun extractZip(
        zipFile: File,
        extractTo: File,
        structure: ZipStructure,
        symlinkLines: MutableList<String>
    ): Int {
        var count = 0
        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                val rawName = entry.name

                if (rawName == "SYMLINKS.txt") {
                    symlinkLines.addAll(zis.bufferedReader().readLines())
                    zis.closeEntry()
                    entry = zis.nextEntry
                    continue
                }

                // Strip data/data/com.termux/files/ prefix if present
                val name = when (structure) {
                    ZipStructure.HAS_DATA_PREFIX -> {
                        val marker = "files/"
                        val idx = rawName.indexOf(marker)
                        if (idx >= 0) rawName.substring(idx + marker.length) else rawName
                    }
                    else -> rawName.trimStart('/', '.')
                }

                if (name.isEmpty()) {
                    zis.closeEntry()
                    entry = zis.nextEntry
                    continue
                }

                val outFile = File(extractTo, name)

                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { fos ->
                        val buf = ByteArray(16_384)
                        var n: Int
                        while (zis.read(buf).also { n = it } != -1) fos.write(buf, 0, n)
                    }
                    count++
                }

                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return count
    }

    // ── Symlinks ──────────────────────────────────────────────────────────────

    /**
     * SYMLINKS.txt: one line per symlink in format  target←linkpath
     * Paths are relative to bootstrap root (same as zip entries).
     */
    private fun createSymlinks(root: File, lines: List<String>) {
        var ok = 0; var fail = 0
        for (line in lines) {
            // The separator is ← (U+2190, 3 bytes: E2 86 90)
            val sepIdx = line.indexOf('\u2190')
            if (sepIdx < 0) continue

            val target   = line.substring(0, sepIdx).trim()
            val linkPath = line.substring(sepIdx + 1).trim()
            val linkFile = File(root, linkPath)

            linkFile.parentFile?.mkdirs()

            try {
                // Remove existing
                val path = linkFile.toPath()
                if (java.nio.file.Files.isSymbolicLink(path) || linkFile.exists()) {
                    linkFile.delete()
                }
                val result = ProcessBuilder("ln", "-sf", target, linkFile.absolutePath)
                    .redirectErrorStream(true).start().waitFor()
                if (result == 0) ok++ else { fail++; Log.w(TAG, "ln failed: $linkPath → $target") }
            } catch (e: Exception) {
                fail++
                Log.w(TAG, "Symlink exception: $linkPath → $target : ${e.message}")
            }
        }
        Log.i(TAG, "Symlinks done: $ok ok, $fail failed")
    }

    // ── chmod ─────────────────────────────────────────────────────────────────

    private fun makeBinariesExecutable(prefix: File) {
        listOf("bin", "libexec", "lib/apt/methods").forEach { dir ->
            File(prefix, dir).listFiles()?.forEach { it.setExecutable(true, false) }
        }
    }

    // ── Uninstall ─────────────────────────────────────────────────────────────

    fun uninstall(ctx: Context) {
        bootstrapRootDir(ctx).deleteRecursively()
        Log.i(TAG, "Bootstrap removed.")
    }
}