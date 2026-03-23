package com.arata.yukarilauncher.termux

import android.util.Log
import java.io.File

/**
 * TermuxPrefixHelper
 *
 * Provides easy access to the Termux filesystem:
 *   - $PREFIX  → /data/data/com.termux/files/usr
 *   - $HOME    → /data/data/com.termux/files/home
 *
 * Note on access permissions:
 *   On unrooted devices, your app can read Termux's files ONLY if:
 *   (a) Both apps share the same `android:sharedUserId` (requires re-signing both), OR
 *   (b) Termux has world-readable files (it sometimes allows this for $HOME), OR
 *   (c) The user explicitly ran `termux-setup-storage` and shared files via
 *       Android's shared storage (/sdcard/Android/data/...).
 *
 *   For typical use without root, the safest approach is to work with files that
 *   Termux has deliberately placed in shared storage, or use the RUN_COMMAND
 *   intent to have Termux copy/move files into a shared location.
 */
object TermuxPrefixHelper {

    private const val TAG = "TermuxPrefixHelper"

    // ─────────────────────────────────────────────────────────────────────────
    // Key directories
    // ─────────────────────────────────────────────────────────────────────────

    val prefixDir   get() = File(TermuxIntegrationManager.TERMUX_PREFIX_DIR)
    val homeDir     get() = File(TermuxIntegrationManager.TERMUX_HOME_DIR)
    val tmpDir      get() = File("${TermuxIntegrationManager.TERMUX_PREFIX_DIR}/tmp")
    val binDir      get() = File("${TermuxIntegrationManager.TERMUX_PREFIX_DIR}/bin")
    val libDir      get() = File("${TermuxIntegrationManager.TERMUX_PREFIX_DIR}/lib")
    val etcDir      get() = File("${TermuxIntegrationManager.TERMUX_PREFIX_DIR}/etc")
    val shareDir    get() = File("${TermuxIntegrationManager.TERMUX_PREFIX_DIR}/share")

    // ─────────────────────────────────────────────────────────────────────────
    // Existence & accessibility checks
    // ─────────────────────────────────────────────────────────────────────────

    data class TermuxAccessInfo(
        val termuxInstalled: Boolean,
        val prefixReadable: Boolean,
        val homeReadable: Boolean,
        val prefixPath: String,
        val homePath: String
    )

    fun getAccessInfo(): TermuxAccessInfo {
        val prefixExists  = prefixDir.exists()
        val homeExists    = homeDir.exists()
        return TermuxAccessInfo(
            termuxInstalled = prefixExists,
            prefixReadable  = prefixExists && prefixDir.canRead(),
            homeReadable    = homeExists   && homeDir.canRead(),
            prefixPath      = prefixDir.absolutePath,
            homePath        = homeDir.absolutePath
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // File listing
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Lists files in a Termux directory.
     * Returns an empty list if the directory is not accessible.
     */
    fun listFiles(dir: File): List<FileEntry> {
        if (!dir.exists() || !dir.canRead()) {
            Log.w(TAG, "Cannot read directory: ${dir.absolutePath}")
            return emptyList()
        }
        return dir.listFiles()
            ?.map { f ->
                FileEntry(
                    name        = f.name,
                    path        = f.absolutePath,
                    isDirectory = f.isDirectory,
                    size        = if (f.isFile) f.length() else 0L,
                    lastModified = f.lastModified()
                )
            }
            ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            ?: emptyList()
    }

    fun listPrefix()  = listFiles(prefixDir)
    fun listHome()    = listFiles(homeDir)
    fun listBin()     = listFiles(binDir)

    /**
     * Finds a binary in $PREFIX/bin by name.
     * Returns its File, or null if not found.
     */
    fun findBinary(name: String): File? {
        val f = File("${TermuxIntegrationManager.TERMUX_PREFIX_DIR}/bin/$name")
        return if (f.exists()) f else null
    }

    /**
     * Returns true if a given package binary exists in $PREFIX/bin,
     * which is a rough indicator that the package is installed.
     * Examples: isCommandAvailable("python3"), isCommandAvailable("node")
     */
    fun isCommandAvailable(command: String): Boolean {
        return findBinary(command) != null
    }

    /**
     * Returns the content of a text file in Termux's filesystem.
     * Returns null if the file is not readable.
     */
    fun readTextFile(path: String): String? {
        val f = File(path)
        return if (f.exists() && f.canRead() && f.isFile) {
            try { f.readText() } catch (e: Exception) {
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
            isDirectory    -> "dir"
            size < 1024    -> "${size} B"
            size < 1024*1024 -> "${"%.1f".format(size/1024f)} KB"
            else           -> "${"%.1f".format(size/1024f/1024f)} MB"
        }
    }
}
