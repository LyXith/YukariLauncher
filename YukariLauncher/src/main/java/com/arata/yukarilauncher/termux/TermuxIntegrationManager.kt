package com.arata.yukarilauncher.termux

import android.content.Context
import android.content.Intent

/**
 * TermuxIntegrationManager
 *
 * Entry point for the launcher's embedded terminal.
 * No external Termux app required or referenced.
 */
object TermuxIntegrationManager {

    fun openEmbeddedTerminal(context: Context, initialCommand: String? = null) {
        val intent = Intent(context, EmbeddedTerminalActivity::class.java).apply {
            if (!initialCommand.isNullOrBlank())
                putExtra(EmbeddedTerminalActivity.EXTRA_INITIAL_COMMAND, initialCommand)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}