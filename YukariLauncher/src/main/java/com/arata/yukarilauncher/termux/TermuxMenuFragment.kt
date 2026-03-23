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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * TermuxMenuFragment
 *
 * Drop-in Fragment exposing all 4 Termux features as buttons.
 * Attach to your settings screen or navigation drawer.
 *
 * Option A — open embedded terminal from any Activity/Fragment:
 *   TermuxIntegrationManager.openEmbeddedTerminal(requireContext())
 *
 * Option B — add to nav_graph.xml:
 *   <fragment
 *       android:id="@+id/termuxMenuFragment"
 *       android:name="com.arata.yukarilauncher.termux.TermuxMenuFragment"
 *       android:label="Terminal" />
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
        root.addView(TextView(ctx).apply {
            text = buildStatusText()
            textSize = 13f
        })

        // ── Launch Termux app ─────────────────────────────────────────────────
        root.addView(makeButton("Open Termux App") {
            TermuxIntegrationManager.launchTermux(ctx)
        })

        // ── Embedded terminal ─────────────────────────────────────────────────
        root.addView(makeButton("Open Embedded Terminal") {
            TermuxIntegrationManager.openEmbeddedTerminal(ctx)
        })

        // ── Run command in embedded terminal ──────────────────────────────────
        root.addView(makeButton("Run: uname -a (embedded)") {
            TermuxIntegrationManager.openEmbeddedTerminal(ctx, "uname -a && sleep 2")
        })

        // ── Run command visibly in Termux app ─────────────────────────────────
        root.addView(makeButton("Run: pkg update (in Termux)") {
            TermuxBridge.runInTermux(ctx, "pkg update")
        })

        // ── List $PREFIX/bin via local exec ───────────────────────────────────
        root.addView(makeButton("List Termux commands (local exec)") {
            lifecycleScope.launch {
                val result = TermuxBridge.executeLocally(
                    "ls ${TermuxIntegrationManager.TERMUX_PREFIX_DIR}/bin | head -20"
                )
                Toast.makeText(
                    ctx,
                    if (result.success) result.stdout.take(200) else "Error: ${result.stderr}",
                    Toast.LENGTH_LONG
                ).show()
            }
        })

        // ── Termux:API clipboard ──────────────────────────────────────────────
        root.addView(makeButton("Copy launcher name (Termux:API)") {
            TermuxIntegrationManager.apiCopyToClipboard(ctx, "YukariLauncher")
            TermuxIntegrationManager.apiToast(ctx, "Copied to clipboard!")
        })

        // ── PREFIX access info ────────────────────────────────────────────────
        // NOTE: Use '$' + "PREFIX" to avoid Kotlin treating it as a string template
        root.addView(makeButton("\$PREFIX access info") {
            val info = TermuxPrefixHelper.getAccessInfo()
            Toast.makeText(
                ctx,
                "Termux installed: ${info.termuxInstalled}\n" +
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
        val termuxOk = TermuxIntegrationManager.isTermuxInstalled(ctx)
        val apiOk    = TermuxIntegrationManager.isTermuxApiInstalled(ctx)
        val prefix   = TermuxPrefixHelper.getAccessInfo()
        return "Termux installed:     ${if (termuxOk) "✓" else "✗"}\n" +
               "Termux:API installed: ${if (apiOk)    "✓" else "✗"}\n" +
               "PREFIX readable:      ${if (prefix.prefixReadable) "✓" else "✗"}\n" +
               "HOME readable:        ${if (prefix.homeReadable)   "✓" else "✗"}"
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
