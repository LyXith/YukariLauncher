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

object TermuxBootstrapInstaller {

    private const val TAG = "BootstrapInstaller"

    // ── Directories ───────────────────────────────────────────────────────────

    fun bootstrapRootDir(ctx: Context) = File(ctx.filesDir, "bootstrap")
    fun prefixDir(ctx: Context)        = File(bootstrapRootDir(ctx), "usr")
    fun homeDir(ctx: Context)          = File(bootstrapRootDir(ctx), "home")

    fun isInstalled(ctx: Context): Boolean =
        File(prefixDir(ctx), "bin/bash").exists()

    // ── Progress ──────────────────────────────────────────────────────────────

    enum class Stage { CHECKING, DOWNLOADING, EXTRACTING, SYMLINKS, DONE, ERROR }
    data class Progress(val stage: Stage, val percent: Int = 0, val error: Throwable? = null)

    // ── ABI + URL ─────────────────────────────────────────────────────────────

    private fun abi(): String = when {
        Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }    -> "aarch64"
        Build.SUPPORTED_ABIS.any { it == "armeabi-v7a" }  -> "arm"
        Build.SUPPORTED_ABIS.any { it == "x86_64" }       -> "x86_64"
        Build.SUPPORTED_ABIS.any { it.startsWith("x86") } -> "i686"
        else -> "aarch64"
    }

    private fun bootstrapUrl(): String =
        "https://github.com/termux/termux-packages/releases/latest/download/bootstrap-${abi()}.zip"

    // ── Zip structure ─────────────────────────────────────────────────────────

    private enum class ZipStructure {
        HAS_USR_PREFIX,   // entries: usr/bin/bash
        NO_PREFIX,        // entries: bin/bash
        HAS_DATA_PREFIX   // entries: com.arata.yukariluncher.files/usr/...
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
            entries.any { it.startsWith("data/") } -> ZipStructure.HAS_DATA_PREFIX
            entries.any { it.startsWith("usr/") || it.startsWith("home/") } -> ZipStructure.HAS_USR_PREFIX
            else -> ZipStructure.NO_PREFIX
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

        if (conn.responseCode != 200)
            throw Exception("HTTP ${conn.responseCode} from $url")

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

    private fun createSymlinks(root: File, lines: List<String>) {
        var ok = 0; var fail = 0
        for (line in lines) {
            val sepIdx = line.indexOf('\u2190')   // ← character
            if (sepIdx < 0) continue
            val target   = line.substring(0, sepIdx).trim()
            val linkPath = line.substring(sepIdx + 1).trim()
            val linkFile = File(root, linkPath)
            linkFile.parentFile?.mkdirs()
            try {
                if (java.nio.file.Files.isSymbolicLink(linkFile.toPath()) || linkFile.exists())
                    linkFile.delete()
                val result = ProcessBuilder("ln", "-sf", target, linkFile.absolutePath)
                    .redirectErrorStream(true).start().waitFor()
                if (result == 0) ok++ else { fail++; Log.w(TAG, "ln failed: $linkPath → $target") }
            } catch (e: Exception) {
                fail++
                Log.w(TAG, "Symlink exception: $linkPath → $target : ${e.message}")
            }
        }
        Log.i(TAG, "Symlinks: $ok ok, $fail failed")
    }

    // ── chmod ─────────────────────────────────────────────────────────────────

    private fun makeBinariesExecutable(prefix: File) {
        listOf("bin", "libexec").forEach { dir ->
            File(prefix, dir).listFiles()?.forEach { it.setExecutable(true, false) }
        }
    }

    // ── Public install ────────────────────────────────────────────────────────
    // Declared LAST so all private helpers above are already in scope

    fun install(ctx: Context, onProgress: (Progress) -> Unit) {
        try {
            onProgress(Progress(Stage.CHECKING))

            if (isInstalled(ctx)) {
                Log.i(TAG, "Already installed.")
                onProgress(Progress(Stage.DONE))
                return
            }

            bootstrapRootDir(ctx).deleteRecursively()
            bootstrapRootDir(ctx).mkdirs()

            val zipFile = File(ctx.cacheDir, "bootstrap-${abi()}.zip")

            downloadZip(bootstrapUrl(), zipFile) { pct: Int ->
                onProgress(Progress(Stage.DOWNLOADING, pct))
            }
            Log.i(TAG, "Zip size: ${zipFile.length()} bytes")

            onProgress(Progress(Stage.EXTRACTING))
            val structure = detectZipStructure(zipFile)
            Log.i(TAG, "Structure: $structure")

            val extractRoot = when (structure) {
                ZipStructure.HAS_USR_PREFIX  -> bootstrapRootDir(ctx)
                ZipStructure.NO_PREFIX       -> prefixDir(ctx)
                ZipStructure.HAS_DATA_PREFIX -> bootstrapRootDir(ctx)
            }
            extractRoot.mkdirs()

            val symlinkLines = mutableListOf<String>()
            val fileCount = extractZip(zipFile, extractRoot, structure, symlinkLines)
            Log.i(TAG, "Extracted $fileCount files into ${extractRoot.absolutePath}")
            zipFile.delete()

            // Verify
            val bash = File(prefixDir(ctx), "bin/bash")
            Log.i(TAG, "bash exists: ${bash.exists()} → ${bash.absolutePath}")
            if (!bash.exists()) {
                Log.e(TAG, "Contents after extract:")
                bootstrapRootDir(ctx).walkTopDown().take(40).forEach { Log.e(TAG, "  $it") }
                throw Exception("bash not found after extraction (structure=$structure)")
            }

            onProgress(Progress(Stage.SYMLINKS))
            createSymlinks(bootstrapRootDir(ctx), symlinkLines)
            makeBinariesExecutable(prefixDir(ctx))

            homeDir(ctx).mkdirs()
            File(prefixDir(ctx), "tmp").mkdirs()

            onProgress(Progress(Stage.DONE))
            Log.i(TAG, "Bootstrap install complete.")

        } catch (e: Exception) {
            Log.e(TAG, "Bootstrap FAILED: ${e.message}", e)
            onProgress(Progress(Stage.ERROR, error = e))
        }
    }

    // ── Uninstall ─────────────────────────────────────────────────────────────

    fun uninstall(ctx: Context) {
        bootstrapRootDir(ctx).deleteRecursively()
        Log.i(TAG, "Bootstrap removed.")
    }
}
