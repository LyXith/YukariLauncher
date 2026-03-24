package com.arata.yukarilauncher.termux

import android.content.Context
import android.util.Log
import java.io.File

/**
 * TermuxPrefixHelper (Embedded Mode)
 *
 * Works ONLY with embedded bootstrap environment:
 *   /data/data/<your.app>/files/bootstrap/
 */
object TermuxPrefixHelper {

    private const val TAG = "TermuxPrefixHelper"

    // ─────────────────────────────────────────────────────────────────────────
    // Directory helpers (ALL use embedded paths)
    // ─────────────────────────────────────────────────────────────────────────

    private fun prefix(context: Context) =
        TermuxIntegrationManager.getPrefixDir(context)

    private fun home(context: Context) =
        TermuxIntegrationManager.getHomeDir(context)

    private fun tmp(context: Context) =
        TermuxIntegrationManager.getTmpDir(context)

    fun prefixDir(context: Context) = prefix(context)
    fun homeDir(context: Context)   = home(context)
    fun tmpDir(context: Context)    = tmp(context)
    fun binDir(context: Context)    = File(prefix(context), "bin")
    fun libDir(context: Context)    = File(prefix(context), "lib")
    fun etcDir(context: Context)    = File(prefix(context), "etc")
    fun shareDir(context: Context)  = File(prefix(context), "share")

    // ─────────────────────────────────────────────────────────────────────────
    // Access info
    // ─────────────────────────────────────────────────────────────────────────

    data class TermuxAccessInfo(
        val bootstrapExists: Boolean,
        val prefixReadable: Boolean,
        val homeReadable: Boolean,
        val prefixPath: String,
        val homePath: String
    )

    fun getAccessInfo(context: Context): TermuxAccessInfo {
        val prefix = prefixDir(context)
        val home   = homeDir(context)

        val prefixExists = prefix.exists()
        val homeExists   = home.exists()

        return TermuxAccessInfo(
            bootstrapExists = prefixExists,
            prefixReadable  = prefixExists && prefix.canRead(),
            homeReadable    = homeExists && home.canRead(),
            prefixPath      = prefix.absolutePath,
            homePath        = home.absolutePath
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // File listing
    // ─────────────────────────────────────────────────────────────────────────

    fun listFiles(dir: File): List<FileEntry> {
        if (!dir.exists() || !dir.canRead()) {
            Log.w(TAG, "Cannot read directory: ${dir.absolutePath}")
            return emptyList()
        }

        return dir.listFiles()
            ?.map { f ->
                FileEntry(
                    name = f.name,
                    path = f.absolutePath,
                    isDirectory = f.isDirectory,
                    size = if (f.isFile) f.length() else 0L,
                    lastModified = f.lastModified()
                )
            }
            ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            ?: emptyList()
    }

    fun listPrefix(context: Context) = listFiles(prefixDir(context))
    fun listHome(context: Context)   = listFiles(homeDir(context))
    fun listBin(context: Context)    = listFiles(binDir(context))

    // ─────────────────────────────────────────────────────────────────────────
    // Binary helpers
    // ─────────────────────────────────────────────────────────────────────────

    fun findBinary(context: Context, name: String): File? {
        val f = File(binDir(context), name)
        return if (f.exists()) f else null
    }

    fun isCommandAvailable(context: Context, command: String): Boolean {
        return findBinary(context, command) != null
    }

    // ─────────────────────────────────────────────────────────────────────────
    // File reading
    // ─────────────────────────────────────────────────────────────────────────

    fun readTextFile(path: String): String? {
        val f = File(path)
        return if (f.exists() && f.canRead() && f.isFile) {
            try {
                f.readText()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read $path: ${e.message}")
                null
            }
        } else null
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Data classes
    // ─────────────────────────────────────────────────────────────────────────

    data class FileEntry(
        val name: String,
        val path: String,
        val isDirectory: Boolean,
        val size: Long,
        val lastModified: Long
    ) {
        val humanSize: String get() = when {
            isDirectory -> "dir"
            size < 1024 -> "${size} B"
            size < 1024 * 1024 -> "${"%.1f".format(size / 1024f)} KB"
            else -> "${"%.1f".format(size / 1024f / 1024f)} MB"
        }
    }
}
