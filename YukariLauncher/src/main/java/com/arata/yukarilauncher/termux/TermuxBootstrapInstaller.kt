package com.arata.yukarilauncher.termux

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.ByteArrayOutputStream
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

    /**
     * Extracts the bootstrap zip into [prefixDir].
     *
     * Path normalization — handles all known Termux zip layouts:
     *   Layout A:  bin/bash          → kept as-is        → prefix/bin/bash  ✓
     *   Layout B:  usr/bin/bash      → strip "usr/"      → prefix/bin/bash  ✓
     *   Layout C:  ./bin/bash        → strip "./"        → prefix/bin/bash  ✓
     *   Layout D:  data/.../files/bin/bash → strip to after "files/" → prefix/bin/bash ✓
     *
     * SYMLINKS.txt is read safely using a raw byte buffer to avoid
     * BufferedReader over-reading the ZipInputStream and corrupting
     * subsequent entries.
     */
    private fun extractZip(
        zipFile: File,
        prefixDir: File,
        symlinkLines: MutableList<String>
    ): Int {
        var count = 0
        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                val rawName = entry.name
                Log.v(TAG, "ZIP entry: $rawName")

                if (rawName == "SYMLINKS.txt") {
                    // ── Safe read: byte buffer only, no BufferedReader ─────────
                    val baos = ByteArrayOutputStream()
                    val buf = ByteArray(4096)
                    var n: Int
                    while (zis.read(buf).also { n = it } != -1) baos.write(buf, 0, n)
                    val content = baos.toString("UTF-8")
                    symlinkLines.addAll(content.lines().filter { it.isNotBlank() })
                    Log.i(TAG, "Read ${symlinkLines.size} symlink lines")
                    zis.closeEntry()
                    entry = zis.nextEntry
                    continue
                }

                // ── Normalize path to be relative to $PREFIX ──────────────────
                val normalized = normalizePath(rawName)
                if (normalized.isEmpty()) {
                    zis.closeEntry()
                    entry = zis.nextEntry
                    continue
                }

                val outFile = File(prefixDir, normalized)
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
        Log.i(TAG, "Extracted $count files into ${prefixDir.absolutePath}")
        return count
    }

    /**
     * Strips any known prefix from a zip entry name so it is always
     * relative to $PREFIX (usr/).
     */
    private fun normalizePath(raw: String): String {
        var name = raw.replace('\\', '/')

        // Strip com.arata.yukariluncher.files/ or data/data/.../files/usr/
        val filesIdx = name.indexOf("files/")
        if (filesIdx >= 0) {
            name = name.substring(filesIdx + "files/".length)
        }

        // Strip leading usr/ — zip already pre-pended it
        if (name.startsWith("usr/")) name = name.removePrefix("usr/")

        // Strip leading home/ — put home files into their own dir later
        // (we skip home/ entries entirely; home dir is created separately)
        if (name.startsWith("home/")) return ""

        // Strip leading ./ or /
        name = name.trimStart('.', '/')

        return name.trim()
    }

    // ── Symlinks ──────────────────────────────────────────────────────────────

    /**
     * SYMLINKS.txt format:  target←linkpath
     * Both paths are relative to $PREFIX.
     */
    private fun createSymlinks(prefixDir: File, lines: List<String>) {
        var ok = 0; var fail = 0
        for (line in lines) {
            val sepIdx = line.indexOf('\u2190')  // ← U+2190
            if (sepIdx < 0) continue
            val target   = line.substring(0, sepIdx).trim()
            val linkPath = line.substring(sepIdx + 1).trim()

            // linkPath in SYMLINKS.txt may or may not include usr/ prefix — normalize it
            val normalizedLink = normalizePath(linkPath).takeIf { it.isNotEmpty() } ?: continue

            val linkFile = File(prefixDir, normalizedLink)
            linkFile.parentFile?.mkdirs()

            try {
                if (java.nio.file.Files.isSymbolicLink(linkFile.toPath()) || linkFile.exists())
                    linkFile.delete()
                val result = ProcessBuilder("ln", "-sf", target, linkFile.absolutePath)
                    .redirectErrorStream(true).start().waitFor()
                if (result == 0) ok++ else { fail++; Log.w(TAG, "ln failed: $normalizedLink → $target") }
            } catch (e: Exception) {
                fail++
                Log.w(TAG, "Symlink error: $normalizedLink → $target : ${e.message}")
            }
        }
        Log.i(TAG, "Symlinks: $ok ok, $fail failed")
    }

    // ── chmod ─────────────────────────────────────────────────────────────────

    private fun makeBinariesExecutable(prefixDir: File) {
        listOf("bin", "libexec").forEach { dir ->
            File(prefixDir, dir).listFiles()?.forEach { it.setExecutable(true, false) }
        }
    }

    // ── Public install ────────────────────────────────────────────────────────

    fun install(ctx: Context, onProgress: (Progress) -> Unit) {
        try {
            onProgress(Progress(Stage.CHECKING))

            if (isInstalled(ctx)) {
                Log.i(TAG, "Already installed.")
                onProgress(Progress(Stage.DONE))
                return
            }

            // Clean any partial previous install
            bootstrapRootDir(ctx).deleteRecursively()
            bootstrapRootDir(ctx).mkdirs()
            prefixDir(ctx).mkdirs()
            homeDir(ctx).mkdirs()

            val zipFile = File(ctx.cacheDir, "bootstrap-${abi()}.zip")

            downloadZip(bootstrapUrl(), zipFile) { pct: Int ->
                onProgress(Progress(Stage.DOWNLOADING, pct))
            }
            Log.i(TAG, "Zip size: ${zipFile.length()} bytes")

            onProgress(Progress(Stage.EXTRACTING))
            val symlinkLines = mutableListOf<String>()
            // Always extract into prefixDir — normalizePath() handles all layouts
            val fileCount = extractZip(zipFile, prefixDir(ctx), symlinkLines)
            Log.i(TAG, "Extracted $fileCount files")
            zipFile.delete()

            // Verify bash landed correctly
            val bash = File(prefixDir(ctx), "bin/bash")
            Log.i(TAG, "bash exists: ${bash.exists()} at ${bash.absolutePath}")

            if (!bash.exists()) {
                Log.e(TAG, "=== bootstrap/usr contents ===")
                prefixDir(ctx).walkTopDown().take(50).forEach { Log.e(TAG, "  $it") }
                throw Exception("bash not found after extraction")
            }

            onProgress(Progress(Stage.SYMLINKS))
            createSymlinks(prefixDir(ctx), symlinkLines)
            makeBinariesExecutable(prefixDir(ctx))

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