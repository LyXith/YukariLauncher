package com.arata.yukarilauncher.termux

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * TermuxBootstrapInstaller
 *
 * Downloads and extracts the official Termux bootstrap zip.
 *
 * Bootstrap zip structure (what's actually inside the zip):
 *   usr/bin/bash
 *   usr/bin/sh -> bash   (symlink via SYMLINKS.txt)
 *   usr/lib/...
 *   home/
 *   SYMLINKS.txt
 *
 * So we extract relative to bootstrapRootDir (NOT prefixDir).
 * After extraction:
 *   bootstrapRootDir/
 *     usr/bin/bash    ← $PREFIX/bin/bash
 *     usr/lib/...
 *     home/           ← $HOME
 *     tmp -> usr/tmp  ← created manually
 */
object TermuxBootstrapInstaller {

    private const val TAG = "BootstrapInstaller"

    // ── Directory layout ──────────────────────────────────────────────────────

    fun bootstrapRootDir(ctx: Context) = File(ctx.filesDir, "bootstrap")
    fun prefixDir(ctx: Context)        = File(bootstrapRootDir(ctx), "usr")
    fun homeDir(ctx: Context)          = File(bootstrapRootDir(ctx), "home")

    fun isInstalled(ctx: Context): Boolean =
        File(prefixDir(ctx), "bin/bash").exists()

    // ── ABI ───────────────────────────────────────────────────────────────────

    private fun abi(): String = when {
        Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }   -> "aarch64"
        Build.SUPPORTED_ABIS.any { it == "armeabi-v7a" }  -> "arm"
        Build.SUPPORTED_ABIS.any { it == "x86_64" }       -> "x86_64"
        Build.SUPPORTED_ABIS.any { it.startsWith("x86") } -> "i686"
        else -> "aarch64"
    }

    // ── URL ───────────────────────────────────────────────────────────────────

    private fun bootstrapUrl(): String =
        "https://packages.termux.dev/bootstrap/bootstrap-${abi()}.zip"

    // ── Progress model ────────────────────────────────────────────────────────

    enum class Stage { CHECKING, DOWNLOADING, EXTRACTING, SYMLINKS, DONE, ERROR }

    data class Progress(
        val stage: Stage,
        val percent: Int = 0,
        val error: Throwable? = null
    )

    // ── Public install entry point ────────────────────────────────────────────

    fun install(ctx: Context, onProgress: (Progress) -> Unit) {
        try {
            onProgress(Progress(Stage.CHECKING))

            if (isInstalled(ctx)) {
                Log.i(TAG, "Already installed.")
                onProgress(Progress(Stage.DONE))
                return
            }

            // Clean slate in case of a previous partial install
            bootstrapRootDir(ctx).deleteRecursively()
            bootstrapRootDir(ctx).mkdirs()

            // Download
            val zipFile = File(ctx.cacheDir, "bootstrap-${abi()}.zip")
            downloadZip(bootstrapUrl(), zipFile) { pct ->
                onProgress(Progress(Stage.DOWNLOADING, pct))
            }

            // Extract — relative to bootstrapRootDir so usr/ and home/ land correctly
            onProgress(Progress(Stage.EXTRACTING))
            val symlinkLines = mutableListOf<String>()
            extractZip(zipFile, bootstrapRootDir(ctx), symlinkLines)
            zipFile.delete()

            // Symlinks
            onProgress(Progress(Stage.SYMLINKS))
            createSymlinks(bootstrapRootDir(ctx), symlinkLines)

            // chmod +x everything in usr/bin
            makeBinariesExecutable(prefixDir(ctx))

            // Ensure home and tmp dirs exist
            homeDir(ctx).mkdirs()
            File(prefixDir(ctx), "tmp").mkdirs()

            Log.i(TAG, "Bootstrap installed. bash=${File(prefixDir(ctx), "bin/bash").exists()}")
            onProgress(Progress(Stage.DONE))

        } catch (e: Exception) {
            Log.e(TAG, "Bootstrap install failed: ${e.message}", e)
            onProgress(Progress(Stage.ERROR, error = e))
        }
    }

    // ── Download ──────────────────────────────────────────────────────────────

    private fun downloadZip(url: String, dest: File, onPercent: (Int) -> Unit) {
        Log.i(TAG, "Downloading: $url")
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout    = 120_000
        conn.instanceFollowRedirects = true
        conn.connect()

        if (conn.responseCode != 200) {
            throw Exception("HTTP ${conn.responseCode} from $url")
        }

        val total = conn.contentLengthLong
        var downloaded = 0L
        var lastPct = -1

        conn.inputStream.use { input ->
            FileOutputStream(dest).use { output ->
                val buf = ByteArray(16_384)
                var n: Int
                while (input.read(buf).also { n = it } != -1) {
                    output.write(buf, 0, n)
                    downloaded += n
                    if (total > 0) {
                        val pct = (downloaded * 100 / total).toInt()
                        if (pct != lastPct) { lastPct = pct; onPercent(pct) }
                    }
                }
            }
        }
        Log.i(TAG, "Downloaded ${dest.length()} bytes")
    }

    // ── Extract ───────────────────────────────────────────────────────────────

    /**
     * Extracts zip into [extractTo].
     * Zip entries look like:
     *   usr/bin/bash
     *   usr/lib/libssl.so.3
     *   home/
     *   SYMLINKS.txt
     *
     * We extract them directly into [extractTo] so paths become:
     *   extractTo/usr/bin/bash  ← correct
     */
    private fun extractZip(
        zipFile: File,
        extractTo: File,
        symlinkLines: MutableList<String>
    ) {
        var count = 0
        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val name = entry.name

                if (name == "SYMLINKS.txt") {
                    symlinkLines.addAll(zis.bufferedReader().readLines())
                    zis.closeEntry()
                    entry = zis.nextEntry
                    continue
                }

                // Strip any leading "./" just in case
                val cleanName = name.trimStart('/', '.')
                if (cleanName.isEmpty()) {
                    zis.closeEntry()
                    entry = zis.nextEntry
                    continue
                }

                val outFile = File(extractTo, cleanName)

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
        Log.i(TAG, "Extracted $count files into ${extractTo.absolutePath}")
    }

    // ── Symlinks ──────────────────────────────────────────────────────────────

    /**
     * SYMLINKS.txt format — one symlink per line:
     *   <target>←<linkPath>
     * Both paths are relative to the bootstrap root (same as zip entries).
     */
    private fun createSymlinks(root: File, lines: List<String>) {
        var ok = 0; var fail = 0
        for (line in lines) {
            val idx = line.indexOf('←')
            if (idx < 0) continue
            val target   = line.substring(0, idx).trim()
            val linkPath = line.substring(idx + 1).trim()  // '←' is 3 bytes in UTF-8
            val linkFile = File(root, linkPath)
            linkFile.parentFile?.mkdirs()
            try {
                // Delete stale file/link first
                if (linkFile.exists() || java.nio.file.Files.isSymbolicLink(linkFile.toPath())) {
                    linkFile.delete()
                }
                val result = ProcessBuilder("ln", "-sf", target, linkFile.absolutePath)
                    .redirectErrorStream(true).start().waitFor()
                if (result == 0) ok++ else fail++
            } catch (e: Exception) {
                Log.w(TAG, "Symlink failed: $linkPath → $target : ${e.message}")
                fail++
            }
        }
        Log.i(TAG, "Symlinks: $ok created, $fail failed")
    }

    // ── chmod ─────────────────────────────────────────────────────────────────

    private fun makeBinariesExecutable(prefix: File) {
        File(prefix, "bin").listFiles()?.forEach { it.setExecutable(true, false) }
        File(prefix, "libexec").walkTopDown().filter { it.isFile }.forEach {
            it.setExecutable(true, false)
        }
        File(prefix, "lib").walkTopDown()
            .filter { it.isFile && !it.name.endsWith(".so") }.forEach {
                it.setExecutable(true, false)
            }
    }

    // ── Uninstall ─────────────────────────────────────────────────────────────

    fun uninstall(ctx: Context) {
        bootstrapRootDir(ctx).deleteRecursively()
        Log.i(TAG, "Bootstrap removed.")
    }
}