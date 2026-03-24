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
 * Downloads and extracts the official Termux bootstrap into the launcher's
 * own private storage — no Termux app required.
 *
 * Layout after install:
 *   context.filesDir/
 *     bootstrap/
 *       usr/      ← $PREFIX  (bin, lib, etc, share …)
 *       home/     ← $HOME
 *       tmp/      ← $TMPDIR
 *
 * The bootstrap zip contains a SYMLINKS.txt that lists symlinks to create
 * (format: "target←linkpath" per line). This installer handles that too.
 */
object TermuxBootstrapInstaller {

    private const val TAG = "BootstrapInstaller"

    // ── Paths ─────────────────────────────────────────────────────────────────

    fun bootstrapRootDir(ctx: Context) = File(ctx.filesDir, "bootstrap")
    fun prefixDir(ctx: Context)        = File(bootstrapRootDir(ctx), "usr")
    fun homeDir(ctx: Context)          = File(bootstrapRootDir(ctx), "home")
    fun tmpDir(ctx: Context)           = File(prefixDir(ctx), "tmp")

    /** True if the bootstrap is already extracted. */
    fun isInstalled(ctx: Context): Boolean {
        val bash = File(prefixDir(ctx), "bin/bash")
        return bash.exists()
    }

    // ── ABI detection ─────────────────────────────────────────────────────────

    private fun bootstrapAbi(): String {
        val supported = Build.SUPPORTED_ABIS
        return when {
            supported.any { it == "arm64-v8a" }   -> "aarch64"
            supported.any { it == "armeabi-v7a" }  -> "arm"
            supported.any { it == "x86_64" }       -> "x86_64"
            supported.any { it.startsWith("x86") } -> "i686"
            else -> "aarch64"  // safe default
        }
    }

    // ── Bootstrap URL ─────────────────────────────────────────────────────────

    private fun bootstrapUrl(): String {
        val abi = bootstrapAbi()
        // Official Termux bootstrap zip from packages.termux.dev
        return "https://packages.termux.dev/bootstrap/bootstrap-${abi}.zip"
    }

    // ── Install ───────────────────────────────────────────────────────────────

    data class Progress(
        val stage: Stage,
        val percent: Int = 0,       // 0–100 during DOWNLOADING
        val error: Throwable? = null
    )

    enum class Stage {
        CHECKING, DOWNLOADING, EXTRACTING, SYMLINKS, DONE, ERROR
    }

    /**
     * Installs the bootstrap. Call from a background thread / coroutine.
     * Reports progress via [onProgress] (called on whatever thread this runs on).
     *
     * Example (in a coroutine):
     * ```kotlin
     * lifecycleScope.launch(Dispatchers.IO) {
     *     TermuxBootstrapInstaller.install(context) { progress ->
     *         withContext(Dispatchers.Main) { updateUi(progress) }
     *     }
     * }
     * ```
     */
    fun install(ctx: Context, onProgress: (Progress) -> Unit) {
        try {
            onProgress(Progress(Stage.CHECKING))

            if (isInstalled(ctx)) {
                Log.i(TAG, "Bootstrap already installed — skipping.")
                onProgress(Progress(Stage.DONE))
                return
            }

            // Prepare directories
            val prefix = prefixDir(ctx)
            val home   = homeDir(ctx)
            val tmp    = tmpDir(ctx)
            bootstrapRootDir(ctx).mkdirs()
            prefix.mkdirs()
            home.mkdirs()
            tmp.mkdirs()

            // Download to a temp file
            val zipFile = File(ctx.cacheDir, "bootstrap.zip")
            downloadZip(bootstrapUrl(), zipFile) { percent ->
                onProgress(Progress(Stage.DOWNLOADING, percent))
            }

            // Extract
            onProgress(Progress(Stage.EXTRACTING))
            val symlinkLines = mutableListOf<String>()
            extractZip(zipFile, prefix, symlinkLines)
            zipFile.delete()

            // Create symlinks from SYMLINKS.txt
            onProgress(Progress(Stage.SYMLINKS))
            createSymlinks(prefix, symlinkLines)

            // Make all binaries executable
            makeBinariesExecutable(prefix)

            Log.i(TAG, "Bootstrap installed to: ${prefix.absolutePath}")
            onProgress(Progress(Stage.DONE))

        } catch (e: Exception) {
            Log.e(TAG, "Bootstrap install failed: ${e.message}", e)
            onProgress(Progress(Stage.ERROR, error = e))
        }
    }

    // ── Download ──────────────────────────────────────────────────────────────

    private fun downloadZip(url: String, dest: File, onPercent: (Int) -> Unit) {
        Log.i(TAG, "Downloading bootstrap from: $url")
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout    = 60_000
        conn.connect()

        val total = conn.contentLength.toLong()
        var downloaded = 0L
        var lastPercent = -1

        conn.inputStream.use { input ->
            FileOutputStream(dest).use { output ->
                val buf = ByteArray(8192)
                var n: Int
                while (input.read(buf).also { n = it } != -1) {
                    output.write(buf, 0, n)
                    downloaded += n
                    if (total > 0) {
                        val pct = (downloaded * 100 / total).toInt()
                        if (pct != lastPercent) {
                            lastPercent = pct
                            onPercent(pct)
                        }
                    }
                }
            }
        }
        Log.i(TAG, "Download complete: ${dest.length()} bytes")
    }

    // ── Extract ───────────────────────────────────────────────────────────────

    private fun extractZip(zipFile: File, prefix: File, symlinkLines: MutableList<String>) {
        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val name = entry.name

                if (name == "SYMLINKS.txt") {
                    // Read symlink definitions
                    symlinkLines.addAll(zis.bufferedReader().readLines())
                    zis.closeEntry()
                    entry = zis.nextEntry
                    continue
                }

                val outFile = File(prefix, name)

                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { fos ->
                        val buf = ByteArray(8192)
                        var n: Int
                        while (zis.read(buf).also { n = it } != -1) {
                            fos.write(buf, 0, n)
                        }
                    }
                }

                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    // ── Symlinks ──────────────────────────────────────────────────────────────

    /**
     * SYMLINKS.txt format (one per line):
     *   <target>←<linkPath>
     * Both paths are relative to $PREFIX.
     */
    private fun createSymlinks(prefix: File, lines: List<String>) {
        var created = 0
        for (line in lines) {
            val parts = line.split("←")
            if (parts.size != 2) continue

            val target   = parts[0].trim()
            val linkPath = parts[1].trim()

            val linkFile = File(prefix, linkPath)
            linkFile.parentFile?.mkdirs()

            try {
                // Use OS symlink via ProcessBuilder (no root needed for app's own files)
                val result = ProcessBuilder("ln", "-sf", target, linkFile.absolutePath)
                    .redirectErrorStream(true)
                    .start()
                    .waitFor()
                if (result == 0) created++
            } catch (e: Exception) {
                Log.w(TAG, "Failed to create symlink $linkPath → $target: ${e.message}")
            }
        }
        Log.i(TAG, "Created $created symlinks")
    }

    // ── chmod ─────────────────────────────────────────────────────────────────

    private fun makeBinariesExecutable(prefix: File) {
        val binDir = File(prefix, "bin")
        if (!binDir.exists()) return
        binDir.listFiles()?.forEach { f ->
            if (f.isFile) f.setExecutable(true, false)
        }
        // Also lib executables
        File(prefix, "lib").walkTopDown().filter { it.isFile }.forEach {
            it.setExecutable(true, false)
        }
        Log.i(TAG, "Made binaries executable in ${binDir.absolutePath}")
    }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    /** Completely removes the bootstrap (for reinstall / uninstall). */
    fun uninstall(ctx: Context) {
        bootstrapRootDir(ctx).deleteRecursively()
        Log.i(TAG, "Bootstrap removed.")
    }
}