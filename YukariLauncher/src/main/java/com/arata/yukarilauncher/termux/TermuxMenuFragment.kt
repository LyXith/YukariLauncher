package com.arata.yukarilauncher.termux

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment

/**
 * TermuxMenuFragment (Embedded Only)
 *
 * Uses ONLY embedded terminal (no external Termux app).
 */
class TermuxMenuFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        // ── Status ────────────────────────────────────────────────────────────
        val statusView = TextView(ctx).apply {
            text = buildStatusText()
            textSize = 13f
        }
        root.addView(statusView)

        // ── Open terminal ─────────────────────────────────────────────────────
        root.addView(makeButton("Open Terminal") {
            TermuxIntegrationManager.openEmbeddedTerminal(ctx)
        })

        // ── Run test command ──────────────────────────────────────────────────
        root.addView(makeButton("Run: uname -a") {
            TermuxIntegrationManager.openEmbeddedTerminal(
                ctx,
                "uname -a; echo; read -p 'Press enter...' "
            )
        })

        // ── List binaries ─────────────────────────────────────────────────────
        root.addView(makeButton("List available commands") {
            val list = TermuxPrefixHelper.listBin(ctx)
                .take(20)
                .joinToString("\n") { it.name }

            Toast.makeText(
                ctx,
                if (list.isNotEmpty()) list else "No commands found",
                Toast.LENGTH_LONG
            ).show()
        })

        // ── PREFIX access info ────────────────────────────────────────────────
        root.addView(makeButton("\$PREFIX info") {
            val info = TermuxPrefixHelper.getAccessInfo(ctx)

            Toast.makeText(
                ctx,
                "Bootstrap exists: ${info.bootstrapExists}\n" +
                "PREFIX readable: ${info.prefixReadable}\n" +
                "HOME readable: ${info.homeReadable}\n" +
                "Path: ${info.prefixPath}",
                Toast.LENGTH_LONG
            ).show()
        })

        return root
    }

    // ─────────────────────────────────────────────────────────────────────────

    private fun buildStatusText(): String {
        val ctx = requireContext()
        val info = TermuxPrefixHelper.getAccessInfo(ctx)

        return "Embedded Termux\n" +
               "PREFIX readable: ${if (info.prefixReadable) "✓" else "✗"}\n" +
               "HOME readable:   ${if (info.homeReadable) "✓" else "✗"}\n" +
               "Path: ${info.prefixPath}"
    }

    private fun makeButton(label: String, onClick: () -> Unit): Button {
        return Button(requireContext()).apply {
            text = label
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 12 }
        }
    }
}
