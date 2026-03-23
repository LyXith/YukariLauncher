package com.arata.yukarilauncher.termux

import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.arata.yukarilauncher.R
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

class EmbeddedTerminalActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_INITIAL_COMMAND = "initial_command"
        private const val TAG = "EmbeddedTerminal"

        private val DEFAULT_SHELL: String
            get() {
                val termuxBash = "/data/data/com.termux/files/usr/bin/bash"
                return if (java.io.File(termuxBash).exists()) termuxBash else "/system/bin/sh"
            }
    }

    private lateinit var terminalView: TerminalView
    private var terminalSession: TerminalSession? = null

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_embedded_terminal)

        terminalView = findViewById(R.id.terminal_view)
        setupTerminalView()
        createSession()
        setupToolbar()
    }

    // NOTE: TerminalView in v0.118.0 does NOT have onResume()/onPause() —
    //       those methods were removed. Nothing needed here.

    override fun onDestroy() {
        super.onDestroy()
        terminalSession?.finishIfRunning()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Terminal setup
    // ─────────────────────────────────────────────────────────────────────────

    private fun setupTerminalView() {
        terminalView.setTextSize(28)
        terminalView.keepScreenOn = true
        terminalView.isFocusable = true
        terminalView.isFocusableInTouchMode = true
        terminalView.requestFocus()

        terminalView.setTerminalViewClient(object : TerminalViewClient {

            // ── Logging ───────────────────────────────────────────────────────
            override fun logError(tag: String?, message: String?)   { Log.e(tag ?: TAG, message ?: "") }
            override fun logWarn(tag: String?, message: String?)    { Log.w(tag ?: TAG, message ?: "") }
            override fun logInfo(tag: String?, message: String?)    { Log.i(tag ?: TAG, message ?: "") }
            override fun logDebug(tag: String?, message: String?)   { Log.d(tag ?: TAG, message ?: "") }
            override fun logVerbose(tag: String?, message: String?) { Log.v(tag ?: TAG, message ?: "") }
            override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) { Log.e(tag ?: TAG, message, e) }
            override fun logStackTrace(tag: String?, e: Exception?) { Log.e(tag ?: TAG, "Stack trace", e) }

            // ── Touch / scale ─────────────────────────────────────────────────
            override fun onScale(scale: Float): Float = scale.coerceIn(0.5f, 3.0f)

            override fun onSingleTapUp(e: MotionEvent?) {
                showSoftKeyboard()
            }

            // ── Key events — required abstract in v0.118.0 ────────────────────
            override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean = false
            override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean = false

            // ── Misc ──────────────────────────────────────────────────────────
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
        val workDir = TermuxIntegrationManager.getHomeDir()?.absolutePath
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
                val text = cb.primaryClip?.getItemAt(0)?.text?.toString()
                if (text != null) session?.write(text)
            }
            override fun onBell(session: TerminalSession) {}
            override fun onColorsChanged(session: TerminalSession) {}
            override fun onTerminalCursorStateChange(state: Boolean) {}

            // NOTE: setTerminalShellPid was removed in v0.118.0 — do NOT override it

            override fun getTerminalCursorStyle(): Int =
                TerminalEmulator.TERMINAL_CURSOR_STYLE_UNDERLINE

            override fun logError(tag: String?, message: String?)   { Log.e(tag ?: TAG, message ?: "") }
            override fun logWarn(tag: String?, message: String?)    { Log.w(tag ?: TAG, message ?: "") }
            override fun logInfo(tag: String?, message: String?)    { Log.i(tag ?: TAG, message ?: "") }
            override fun logDebug(tag: String?, message: String?)   { Log.d(tag ?: TAG, message ?: "") }
            override fun logVerbose(tag: String?, message: String?) { Log.v(tag ?: TAG, message ?: "") }
            override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) { Log.e(tag ?: TAG, message, e) }
            override fun logStackTrace(tag: String?, e: Exception?) { Log.e(tag ?: TAG, "Stack trace", e) }
        }

        terminalSession = TerminalSession(
            DEFAULT_SHELL,
            workDir,
            arrayOf<String>(),
            buildEnvironment(),
            TerminalEmulator.DEFAULT_TERMINAL_TRANSCRIPT_ROWS,
            client
        )

        terminalView.attachSession(terminalSession)

        if (!initialCommand.isNullOrBlank()) {
            terminalSession?.write("$initialCommand\n")
        }
    }

    private fun buildEnvironment(): Array<String> {
        val prefix = TermuxIntegrationManager.TERMUX_PREFIX_DIR
        val home   = TermuxIntegrationManager.TERMUX_HOME_DIR
        return arrayOf(
            "TERM=xterm-256color",
            "COLORTERM=truecolor",
            "HOME=$home",
            "PREFIX=$prefix",
            "PATH=$prefix/bin:$prefix/bin/applets:/system/bin:/system/xbin",
            "TMPDIR=$prefix/tmp",
            "LANG=en_US.UTF-8",
            "SHELL=$DEFAULT_SHELL",
            "LD_LIBRARY_PATH=$prefix/lib"
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Toolbar
    // ─────────────────────────────────────────────────────────────────────────

    private fun setupToolbar() {
        // Ctrl — send ASCII control character for the NEXT key manually
        // (sendControlKey() was removed; we simulate Ctrl+C as a common default)
        findViewById<ImageButton>(R.id.btn_ctrl)?.setOnClickListener {
            // Write ETX (Ctrl+C) — most useful default; adapt if needed
            terminalSession?.write("\u0003")
        }
        // ESC — write escape byte as a String
        findViewById<ImageButton>(R.id.btn_esc)?.setOnClickListener {
            terminalSession?.write("\u001b")   // ESC as String, not ByteArray
        }
        // Tab
        findViewById<ImageButton>(R.id.btn_tab)?.setOnClickListener {
            terminalSession?.write("\t")
        }
        // Arrow up
        findViewById<ImageButton>(R.id.btn_arrow_up)?.setOnClickListener {
            terminalSession?.write("\u001b[A")
        }
        // Arrow down
        findViewById<ImageButton>(R.id.btn_arrow_down)?.setOnClickListener {
            terminalSession?.write("\u001b[B")
        }
        // Keyboard
        findViewById<ImageButton>(R.id.btn_keyboard)?.setOnClickListener {
            showSoftKeyboard()
        }
        // Close
        findViewById<ImageButton>(R.id.btn_close)?.setOnClickListener {
            finish()
        }
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
}
