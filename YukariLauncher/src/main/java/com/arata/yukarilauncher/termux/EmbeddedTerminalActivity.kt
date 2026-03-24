package com.arata.yukarilauncher.termux

import android.content.Context
import android.os.Bundle
import android.os.Process
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.arata.yukarilauncher.R
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class EmbeddedTerminalActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_INITIAL_COMMAND = "initial_command"
        private const val TAG = "EmbeddedTerminal"
    }

    // Views
    private lateinit var terminalView: TerminalView
    private lateinit var terminalMainLayout: LinearLayout  // FIX: need parent ref
    private lateinit var loadingLayout: LinearLayout
    private lateinit var loadingText: TextView
    private lateinit var loadingBar: ProgressBar

    private var terminalSession: TerminalSession? = null

    // Resolved paths
    private var prefixDir: String = "/system"
    private var homeDir: String   = "/data/local/tmp"
    private var shellPath: String = "/system/bin/sh"

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_embedded_terminal)

        terminalView       = findViewById(R.id.terminal_view)
        terminalMainLayout = findViewById(R.id.terminal_main_layout)  // FIX: grab parent
        loadingLayout      = findViewById(R.id.loading_layout)
        loadingText        = findViewById(R.id.loading_text)
        loadingBar         = findViewById(R.id.loading_bar)

        terminalView.isFocusable = true
        terminalView.isFocusableInTouchMode = true

        // Show loading immediately so screen isn't blank
        showLoading("Checking environment…")
        setupEnvironmentThenStart()
    }

    override fun onDestroy() {
        super.onDestroy()
        terminalSession?.finishIfRunning()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Environment resolution
    // ─────────────────────────────────────────────────────────────────────────

    private fun setupEnvironmentThenStart() {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { resolveEnvironment() }
            } catch (e: Exception) {
                Log.e(TAG, "Environment setup failed: ${e.message}", e)
                // Fall back to system sh — still usable
                prefixDir = filesDir.absolutePath
                homeDir   = filesDir.absolutePath
                shellPath = "/system/bin/sh"
            }

            // FIX: show terminal_main_layout (the parent), not just terminalView
            hideLoading()
            setupTerminalView()
            createSession()
            setupToolbar()
            terminalView.requestFocus()
        }
    }

    private suspend fun resolveEnvironment() {
        // Option 1: Termux already installed and accessible
        val termuxBash = "${TermuxIntegrationManager.TERMUX_PREFIX_DIR}/bin/bash"
        if (java.io.File(termuxBash).let { it.exists() && it.canExecute() }) {
            prefixDir = TermuxIntegrationManager.TERMUX_PREFIX_DIR
            homeDir   = TermuxIntegrationManager.TERMUX_HOME_DIR
            shellPath = termuxBash
            Log.i(TAG, "Using Termux prefix: $prefixDir")
            return
        }

        // Option 2: launcher-private bootstrap
        val localPrefix = TermuxBootstrapInstaller.prefixDir(this@EmbeddedTerminalActivity)
        val localHome   = TermuxBootstrapInstaller.homeDir(this@EmbeddedTerminalActivity)
        val localBash   = java.io.File(localPrefix, "bin/bash")

        if (!localBash.exists()) {
            withContext(Dispatchers.Main) { showLoading("Setting up environment…") }
            TermuxBootstrapInstaller.install(this@EmbeddedTerminalActivity) { progress ->
                runBlocking {
                    withContext(Dispatchers.Main) {
                        when (progress.stage) {
                            TermuxBootstrapInstaller.Stage.DOWNLOADING ->
                                showLoading("Downloading bootstrap… ${progress.percent}%", progress.percent)
                            TermuxBootstrapInstaller.Stage.EXTRACTING ->
                                showLoading("Extracting…")
                            TermuxBootstrapInstaller.Stage.SYMLINKS ->
                                showLoading("Setting up symlinks…")
                            TermuxBootstrapInstaller.Stage.ERROR ->
                                showLoading("Bootstrap failed — using fallback shell")
                            else -> {}
                        }
                    }
                }
            }
        }

        if (localBash.exists()) {
            prefixDir = localPrefix.absolutePath
            homeDir   = localHome.absolutePath
            shellPath = localBash.absolutePath
            Log.i(TAG, "Using local bootstrap: $prefixDir")
            return
        }

        // Option 3: system sh fallback
        Log.w(TAG, "Falling back to /system/bin/sh")
        prefixDir = filesDir.absolutePath
        homeDir   = filesDir.absolutePath
        shellPath = "/system/bin/sh"
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Terminal
    // ─────────────────────────────────────────────────────────────────────────

    private fun setupTerminalView() {
        terminalView.setTextSize(28)
        terminalView.keepScreenOn = true

        terminalView.setTerminalViewClient(object : TerminalViewClient {
            override fun logError(tag: String?, message: String?)   { Log.e(tag ?: TAG, message ?: "") }
            override fun logWarn(tag: String?, message: String?)    { Log.w(tag ?: TAG, message ?: "") }
            override fun logInfo(tag: String?, message: String?)    { Log.i(tag ?: TAG, message ?: "") }
            override fun logDebug(tag: String?, message: String?)   { Log.d(tag ?: TAG, message ?: "") }
            override fun logVerbose(tag: String?, message: String?) { Log.v(tag ?: TAG, message ?: "") }
            override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) { Log.e(tag ?: TAG, message, e) }
            override fun logStackTrace(tag: String?, e: Exception?) { Log.e(tag ?: TAG, "Stack trace", e) }

            override fun onScale(scale: Float): Float = scale.coerceIn(0.5f, 3.0f)
            override fun onSingleTapUp(e: MotionEvent?) { showSoftKeyboard() }
            override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean = false
            override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean = false
            override fun shouldBackButtonBeMappedToEscape(): Boolean = false
            override fun shouldEnforceCharBasedInput(): Boolean = true
            override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
            override fun isTerminalViewSelected(): Boolean = true
            override fun copyModeChanged(copyMode: Boolean) {}
            override fun onLongPress(event: MotionEvent?): Boolean = false
            override fun readControlKey(): Boolean = false
            override fun readAltKey(): Boolean = false
            override fun readShiftKey(): Boolean = false
            override fun readFnKey(): Boolean = false
            override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean = false
            override fun onEmulatorSet() {}
        })
    }

    private fun createSession() {
        val initialCommand = intent.getStringExtra(EXTRA_INITIAL_COMMAND)
        val workDir = java.io.File(homeDir).takeIf { it.exists() }?.absolutePath
            ?: filesDir.absolutePath

        val client = object : TerminalSessionClient {
            override fun onTextChanged(changedSession: TerminalSession) {
                runOnUiThread { terminalView.onScreenUpdated() }
            }
            override fun onTitleChanged(changedSession: TerminalSession) {}
            override fun onSessionFinished(finishedSession: TerminalSession) {
                runOnUiThread {
                    Toast.makeText(
                        this@EmbeddedTerminalActivity,
                        "Session ended (exit code: ${finishedSession.exitStatus})",
                        Toast.LENGTH_SHORT
                    ).show()
                    finish()
                }
            }
            override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
                val cb = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cb.setPrimaryClip(android.content.ClipData.newPlainText("Terminal", text))
            }
            override fun onPasteTextFromClipboard(session: TerminalSession?) {
                val cb = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cb.primaryClip?.getItemAt(0)?.text?.toString()?.let { session?.write(it) }
            }
            override fun onBell(session: TerminalSession) {}
            override fun onColorsChanged(session: TerminalSession) {}
            override fun onTerminalCursorStateChange(state: Boolean) {}
            override fun getTerminalCursorStyle(): Int = TerminalEmulator.TERMINAL_CURSOR_STYLE_UNDERLINE
            override fun logError(tag: String?, message: String?)   { Log.e(tag ?: TAG, message ?: "") }
            override fun logWarn(tag: String?, message: String?)    { Log.w(tag ?: TAG, message ?: "") }
            override fun logInfo(tag: String?, message: String?)    { Log.i(tag ?: TAG, message ?: "") }
            override fun logDebug(tag: String?, message: String?)   { Log.d(tag ?: TAG, message ?: "") }
            override fun logVerbose(tag: String?, message: String?) { Log.v(tag ?: TAG, message ?: "") }
            override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) { Log.e(tag ?: TAG, message, e) }
            override fun logStackTrace(tag: String?, e: Exception?) { Log.e(tag ?: TAG, "Stack trace", e) }
        }

        terminalSession = TerminalSession(
            shellPath, workDir, arrayOf(),
            buildEnvironment(),
            TerminalEmulator.DEFAULT_TERMINAL_TRANSCRIPT_ROWS,
            client
        )

        terminalView.attachSession(terminalSession)

        if (!initialCommand.isNullOrBlank()) {
            terminalSession?.write("$initialCommand\n")
        }
    }

    private fun buildEnvironment(): Array<String> = arrayOf(
        "TERM=xterm-256color",
        "COLORTERM=truecolor",
        "HOME=$homeDir",
        "PREFIX=$prefixDir",
        "PATH=$prefixDir/bin:$prefixDir/bin/applets:/system/bin:/system/xbin",
        "TMPDIR=$prefixDir/tmp",
        "LANG=en_US.UTF-8",
        "SHELL=$shellPath",
        "LD_LIBRARY_PATH=$prefixDir/lib",
        "USER=shell",
        "LOGNAME=shell",
        "UID=${Process.myUid()}",
        "PS1=\\[\\e[0;32m\\]yukari\\[\\e[0m\\]:\\[\\e[0;34m\\]\\w\\[\\e[0m\\]\\$ ",
        "ANDROID_ROOT=/system",
        "ANDROID_DATA=/data"
    )

    // ─────────────────────────────────────────────────────────────────────────
    // Toolbar
    // ─────────────────────────────────────────────────────────────────────────

    private fun setupToolbar() {
        findViewById<ImageButton>(R.id.btn_ctrl)?.setOnClickListener { terminalSession?.write("\u0003") }
        findViewById<ImageButton>(R.id.btn_esc)?.setOnClickListener  { terminalSession?.write("\u001b") }
        findViewById<ImageButton>(R.id.btn_tab)?.setOnClickListener  { terminalSession?.write("\t") }
        findViewById<ImageButton>(R.id.btn_arrow_up)?.setOnClickListener   { terminalSession?.write("\u001b[A") }
        findViewById<ImageButton>(R.id.btn_arrow_down)?.setOnClickListener { terminalSession?.write("\u001b[B") }
        findViewById<ImageButton>(R.id.btn_keyboard)?.setOnClickListener { showSoftKeyboard() }
        findViewById<ImageButton>(R.id.btn_close)?.setOnClickListener   { finish() }
    }

    private fun showSoftKeyboard() {
        terminalView.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(terminalView.windowToken, 0)
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Loading helpers — FIX: toggle terminal_main_layout, not just terminalView
    // ─────────────────────────────────────────────────────────────────────────

    private fun showLoading(message: String, percent: Int = -1) {
        loadingLayout.visibility      = View.VISIBLE
        terminalMainLayout.visibility = View.GONE   // hide the parent
        loadingText.text = message
        if (percent in 0..100) {
            loadingBar.visibility = View.VISIBLE
            loadingBar.progress   = percent
        } else {
            loadingBar.visibility = View.GONE
        }
    }

    private fun hideLoading() {
        loadingLayout.visibility      = View.GONE
        terminalMainLayout.visibility = View.VISIBLE  // show the parent
    }
}