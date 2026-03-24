package com.arata.yukarilauncher.termux

import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File

/**
 * TermuxIntegrationManager (Embedded Mode)
 *
 * This version is designed ONLY for embedded Termux environment.
 * It does NOT depend on the real Termux app.
 */
object TermuxIntegrationManager {

    private const val TAG = "TermuxIntegration"

    // ─────────────────────────────────────────────────────────────────────────
    // Embedded Paths
    // ─────────────────────────────────────────────────────────────────────────

    fun getBootstrapDir(context: Context): File {
        return File(context.filesDir, "bootstrap")
    }

    fun getPrefixDir(context: Context): File {
        return File(getBootstrapDir(context), "usr")
    }

    fun getHomeDir(context: Context): File {
        return File(getBootstrapDir(context), "home")
    }

    fun getTmpDir(context: Context): File {
        return File(getPrefixDir(context), "tmp")
    }

    fun getShell(context: Context): String {
        val bash = File(getPrefixDir(context), "bin/bash")
        return if (bash.exists()) bash.absolutePath else "/system/bin/sh"
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Embedded Terminal Launcher
    // ─────────────────────────────────────────────────────────────────────────

    fun openEmbeddedTerminal(
        context: Context,
        initialCommand: String? = null
    ) {
        val intent = Intent(context, EmbeddedTerminalActivity::class.java).apply {
            if (initialCommand != null) {
                putExtra(EmbeddedTerminalActivity.EXTRA_INITIAL_COMMAND, initialCommand)
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Environment Builder (VERY IMPORTANT)
    // ─────────────────────────────────────────────────────────────────────────

    fun buildEnvironment(context: Context): Array<String> {
        val prefix = getPrefixDir(context).absolutePath
        val home   = getHomeDir(context).absolutePath
        val tmp    = getTmpDir(context).absolutePath
        val shell  = getShell(context)

        return arrayOf(
            "TERM=xterm-256color",
            "COLORTERM=truecolor",
            "HOME=$home",
            "PREFIX=$prefix",
            "PATH=$prefix/bin:/system/bin:/system/xbin",
            "LD_LIBRARY_PATH=$prefix/lib",
            "TMPDIR=$tmp",
            "LANG=en_US.UTF-8",
            "SHELL=$shell"
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Debug Helpers
    // ─────────────────────────────────────────────────────────────────────────

    fun logPaths(context: Context) {
        Log.d(TAG, "Bootstrap: ${getBootstrapDir(context)}")
        Log.d(TAG, "Prefix: ${getPrefixDir(context)}")
        Log.d(TAG, "Home: ${getHomeDir(context)}")
        Log.d(TAG, "Shell: ${getShell(context)}")
    }
}
