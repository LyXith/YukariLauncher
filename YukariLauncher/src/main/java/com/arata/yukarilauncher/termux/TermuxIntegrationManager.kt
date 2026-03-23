package com.arata.yukarilauncher.termux

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import java.io.File

/**
 * TermuxIntegrationManager
 *
 * Central entry point for all Termux integration features:
 *  - Detect Termux installation
 *  - Launch Termux (with optional command)
 *  - Open embedded terminal
 *  - Access $PREFIX filesystem
 *  - Send Termux:API commands
 */
object TermuxIntegrationManager {

    private const val TAG = "TermuxIntegration"

    const val TERMUX_PACKAGE         = "com.termux"
    const val TERMUX_API_PACKAGE     = "com.termux.api"
    const val TERMUX_FILES_DIR       = "/data/data/com.termux/files"
    const val TERMUX_PREFIX_DIR      = "/data/data/com.termux/files/usr"
    const val TERMUX_HOME_DIR        = "/data/data/com.termux/files/home"

    // ─────────────────────────────────────────────────────────────────────────
    // Detection
    // ─────────────────────────────────────────────────────────────────────────

    /** Returns true if Termux is installed on this device. */
    fun isTermuxInstalled(context: Context): Boolean {
        return isPackageInstalled(context, TERMUX_PACKAGE)
    }

    /** Returns true if Termux:API addon is installed. */
    fun isTermuxApiInstalled(context: Context): Boolean {
        return isPackageInstalled(context, TERMUX_API_PACKAGE)
    }

    private fun isPackageInstalled(context: Context, packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Launch Termux app
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Opens the Termux app.
     * @param startupCommand  Optional shell command to run on open.
     *                        Requires Termux to have allowed external apps
     *                        (Settings → Allow External Apps in Termux).
     */
    fun launchTermux(context: Context, startupCommand: String? = null) {
        if (!isTermuxInstalled(context)) {
            openPlayStorePage(context, TERMUX_PACKAGE)
            return
        }

        if (startupCommand != null) {
            // Use RUN_COMMAND intent (needs allow-external-apps=true in Termux)
            val intent = Intent("com.termux.RUN_COMMAND").apply {
                setPackage(TERMUX_PACKAGE)
                putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
                putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", startupCommand))
                putExtra("com.termux.RUN_COMMAND_WORKDIR", TERMUX_HOME_DIR)
                putExtra("com.termux.RUN_COMMAND_TERMINAL", true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.w(TAG, "RUN_COMMAND failed, falling back to plain launch: ${e.message}")
                launchTermuxPlain(context)
            }
        } else {
            launchTermuxPlain(context)
        }
    }

    private fun launchTermuxPlain(context: Context) {
        val intent = context.packageManager
            .getLaunchIntentForPackage(TERMUX_PACKAGE)
            ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        if (intent != null) {
            context.startActivity(intent)
        } else {
            Log.e(TAG, "Cannot launch Termux — no launch intent found")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Embedded terminal
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Opens the in-app embedded terminal Activity.
     * @param initialCommand  Shell command to run when the terminal opens.
     *                        Defaults to an interactive bash session.
     */
    fun openEmbeddedTerminal(
        context: Context,
        initialCommand: String = "/data/data/com.termux/files/usr/bin/login"
    ) {
        val intent = Intent(context, EmbeddedTerminalActivity::class.java).apply {
            putExtra(EmbeddedTerminalActivity.EXTRA_INITIAL_COMMAND, initialCommand)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Termux:API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Shows a Toast message via Termux:API.
     */
    fun apiToast(context: Context, message: String) {
        sendApiIntent(context, "com.termux.api.Toast") { intent ->
            intent.putExtra("text", message)
            intent.putExtra("short", false)
        }
    }

    /**
     * Copies text to clipboard via Termux:API.
     */
    fun apiCopyToClipboard(context: Context, text: String) {
        sendApiIntent(context, "com.termux.api.Clipboard") { intent ->
            intent.putExtra("text", text)
        }
    }

    /**
     * Sends a Termux:API intent.
     * @param apiAction  Full action string for the desired API (e.g. "com.termux.api.Toast")
     * @param configure  Lambda to put extras on the intent before sending
     */
    fun sendApiIntent(
        context: Context,
        apiAction: String,
        configure: ((Intent) -> Unit)? = null
    ) {
        if (!isTermuxApiInstalled(context)) {
            Log.w(TAG, "Termux:API not installed, cannot send: $apiAction")
            return
        }
        val intent = Intent(apiAction).apply {
            setPackage(TERMUX_API_PACKAGE)
            configure?.invoke(this)
        }
        try {
            context.sendBroadcast(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send Termux:API intent: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // $PREFIX file access
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns the Termux $PREFIX directory as a [File], or null if not accessible.
     * Note: Requires the launcher to run as the same UID as Termux (i.e. both
     * installed with the same sharedUserId), or root access. For normal installs
     * this will only work if Termux has granted file permissions via its API.
     */
    fun getPrefixDir(): File? {
        val dir = File(TERMUX_PREFIX_DIR)
        return if (dir.exists() && dir.canRead()) dir else null
    }

    fun getHomeDir(): File? {
        val dir = File(TERMUX_HOME_DIR)
        return if (dir.exists() && dir.canRead()) dir else null
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun openPlayStorePage(context: Context, packageName: String) {
        val intent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://f-droid.org/packages/$packageName")
        ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        context.startActivity(intent)
    }
}
