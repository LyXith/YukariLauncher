package com.arata.yukarilauncher.termux

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * TermuxBridge (Embedded Mode)
 *
 * Executes commands inside the embedded Termux environment.
 */
object TermuxBridge {

    private const val TAG = "TermuxBridge"

    // ─────────────────────────────────────────────────────────────────────────
    // Result model
    // ─────────────────────────────────────────────────────────────────────────

    data class ExecResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String
    ) {
        val success: Boolean get() = exitCode == 0
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Execute locally (embedded)
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun executeLocally(
        context: Context,
        command: String,
        timeoutMs: Long = 30_000L
    ): ExecResult = withContext(Dispatchers.IO) {

        val prefix = TermuxIntegrationManager.getPrefixDir(context).absolutePath
        val home   = TermuxIntegrationManager.getHomeDir(context).absolutePath
        val tmp    = TermuxIntegrationManager.getTmpDir(context).absolutePath
        val shell  = TermuxIntegrationManager.getShell(context)

        val envMap = mapOf(
            "TERM" to "xterm-256color",
            "HOME" to home,
            "PREFIX" to prefix,
            "PATH" to "$prefix/bin:/system/bin:/system/xbin",
            "TMPDIR" to tmp,
            "LANG" to "en_US.UTF-8",
            "LD_LIBRARY_PATH" to "$prefix/lib",
            "SHELL" to shell
        )

        val workDir = File(home).takeIf { it.exists() } ?: File(context.filesDir.absolutePath)

        return@withContext try {
            val process = ProcessBuilder(shell, "-c", command)
                .directory(workDir)
                .apply {
                    environment().clear()
                    environment().putAll(envMap)
                }
                .start()

            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()

            val exitCode = process.waitFor()

            Log.d(TAG, "exec exit=$exitCode cmd=$command")

            ExecResult(exitCode, stdout.trim(), stderr.trim())
        } catch (e: Exception) {
            Log.e(TAG, "exec error: ${e.message}")
            ExecResult(-1, "", e.message ?: "unknown error")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun listInstalledPackages(context: Context): List<String> {
        val result = executeLocally(context, "pkg list-installed 2>/dev/null")
        return if (result.success) {
            result.stdout.lines().filter { it.isNotBlank() }
        } else emptyList()
    }

    suspend fun getBashVersion(context: Context): String {
        val result = executeLocally(context, "bash --version 2>&1 | head -1")
        return if (result.success) result.stdout else "unknown"
    }
}
