package com.arata.yukarilauncher.termux

import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * TermuxBridge
 *
 * Provides two ways to run commands:
 *
 * 1. [runInTermux]    — Opens Termux with a command visible in its UI
 *                       (uses RUN_COMMAND intent; requires Termux ≥ 0.109
 *                        and allow-external-apps=true in ~/.termux/termux.properties)
 *
 * 2. [executeLocally] — Runs a shell command inside the launcher process itself
 *                       using the Termux $PREFIX shell. Returns stdout/stderr.
 *                       Useful for non-interactive tasks (file ops, package queries).
 */
object TermuxBridge {

    private const val TAG = "TermuxBridge"

    // ─────────────────────────────────────────────────────────────────────────
    // Run a command visibly inside the Termux app
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Sends a RUN_COMMAND intent to Termux, opening a new terminal session
     * that runs [command] visibly in the Termux UI.
     *
     * Requirements:
     *   - Termux ≥ 0.109 installed
     *   - `allow-external-apps=true` in `~/.termux/termux.properties`
     *
     * @param context         Android context
     * @param command         Shell command to run (e.g. "pkg update")
     * @param workDir         Working directory (defaults to Termux home)
     * @param openInTerminal  Whether to open a visible terminal window (default true)
     */
    fun runInTermux(
        context: Context,
        command: String,
        workDir: String = TermuxIntegrationManager.TERMUX_HOME_DIR,
        openInTerminal: Boolean = true
    ) {
        if (!TermuxIntegrationManager.isTermuxInstalled(context)) {
            Log.w(TAG, "Termux not installed — cannot run command: $command")
            return
        }

        val intent = Intent("com.termux.RUN_COMMAND").apply {
            setPackage(TermuxIntegrationManager.TERMUX_PACKAGE)
            putExtra(
                "com.termux.RUN_COMMAND_PATH",
                "${TermuxIntegrationManager.TERMUX_PREFIX_DIR}/bin/bash"
            )
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", command))
            putExtra("com.termux.RUN_COMMAND_WORKDIR", workDir)
            putExtra("com.termux.RUN_COMMAND_TERMINAL", openInTerminal)
            putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0") // new session
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            context.startActivity(intent)
            Log.i(TAG, "Sent RUN_COMMAND to Termux: $command")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send RUN_COMMAND: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Execute a command inside the launcher's own process
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Result of a local shell execution.
     */
    data class ExecResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String
    ) {
        val success: Boolean get() = exitCode == 0
    }

    /**
     * Executes [command] in a subprocess using the Termux bash (or system sh
     * as fallback), returns stdout + stderr as a [ExecResult].
     *
     * This is a **suspending** function — call it from a coroutine or
     * `lifecycleScope.launch(Dispatchers.IO) { ... }`.
     *
     * Example:
     * ```kotlin
     * val result = TermuxBridge.executeLocally("pkg list-installed")
     * if (result.success) Log.d("TAG", result.stdout)
     * ```
     */
    suspend fun executeLocally(
        command: String,
        workDir: String = TermuxIntegrationManager.TERMUX_HOME_DIR,
        timeoutMs: Long = 30_000L
    ): ExecResult = withContext(Dispatchers.IO) {
        val shell = if (java.io.File("${TermuxIntegrationManager.TERMUX_PREFIX_DIR}/bin/bash").exists()) {
            "${TermuxIntegrationManager.TERMUX_PREFIX_DIR}/bin/bash"
        } else {
            "/system/bin/sh"
        }

        val env = arrayOf(
            "HOME=${TermuxIntegrationManager.TERMUX_HOME_DIR}",
            "PREFIX=${TermuxIntegrationManager.TERMUX_PREFIX_DIR}",
            "PATH=${TermuxIntegrationManager.TERMUX_PREFIX_DIR}/bin:/system/bin:/system/xbin",
            "TMPDIR=${TermuxIntegrationManager.TERMUX_PREFIX_DIR}/tmp",
            "LANG=en_US.UTF-8",
            "LD_LIBRARY_PATH=${TermuxIntegrationManager.TERMUX_PREFIX_DIR}/lib"
        )

        val work = java.io.File(workDir).takeIf { it.exists() }
            ?: java.io.File(TermuxIntegrationManager.TERMUX_HOME_DIR)
                .takeIf { it.exists() }

        return@withContext try {
            val process = ProcessBuilder(shell, "-c", command)
                .directory(work)
                .also { pb ->
                    pb.environment().clear()
                    env.forEach { e ->
                        val idx = e.indexOf('=')
                        if (idx > 0) pb.environment()[e.substring(0, idx)] = e.substring(idx + 1)
                    }
                }
                .start()

            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()

            val exited = process.waitFor()
            Log.d(TAG, "executeLocally exit=$exited cmd=$command")
            ExecResult(exited, stdout.trim(), stderr.trim())
        } catch (e: Exception) {
            Log.e(TAG, "executeLocally error: ${e.message}")
            ExecResult(-1, "", e.message ?: "unknown error")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Convenience helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Lists all installed Termux packages. Suspend function. */
    suspend fun listInstalledPackages(): List<String> {
        val result = executeLocally("pkg list-installed 2>/dev/null")
        return if (result.success) {
            result.stdout.lines().filter { it.isNotBlank() }
        } else emptyList()
    }

    /** Returns Termux bash version string. */
    suspend fun getBashVersion(): String {
        val result = executeLocally("bash --version 2>&1 | head -1")
        return if (result.success) result.stdout else "unknown"
    }
}
