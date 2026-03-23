package com.arata.yukarilauncher.termux

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView

/**
 * TermuxFab
 *
 * A self-attaching floating terminal button.
 * Overlays directly onto any Activity's root DecorView —
 * no layout XML changes needed.
 *
 * ── Usage: one line in any Activity.onResume() ────────────────────────────
 *
 *   override fun onResume() {
 *       super.onResume()
 *       TermuxFab.attach(this)
 *   }
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * Recommended: add to LauncherActivity (the main screen after launch).
 * File: src/main/java/net/kdt/pojavlaunch/LauncherActivity.kt  (or .java)
 *
 * If LauncherActivity is Java, call it as:
 *   TermuxFab.INSTANCE.attach(this);   // if Kotlin object
 * or just add the one-liner in the Kotlin file that wraps it.
 * ─────────────────────────────────────────────────────────────────────────────
 */
object TermuxFab {

    private const val FAB_TAG = "termux_fab_button"

    /**
     * Attaches the floating terminal button to [activity].
     * Safe to call multiple times — won't add duplicate buttons.
     */
    fun attach(activity: Activity) {
        val decorView = activity.window.decorView as? FrameLayout ?: return

        // Already attached — skip
        if (decorView.findViewWithTag<View>(FAB_TAG) != null) return

        val fab = buildFab(activity)
        fab.tag = FAB_TAG
        fab.setOnClickListener {
            TermuxIntegrationManager.openEmbeddedTerminal(activity)
        }

        val size = dp(activity, 56)
        val margin = dp(activity, 16)

        val params = FrameLayout.LayoutParams(size, size).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            bottomMargin = margin
            rightMargin  = margin
        }

        decorView.addView(fab, params)
    }

    /** Removes the button (call in onPause/onDestroy if needed) */
    fun detach(activity: Activity) {
        val decorView = activity.window.decorView as? FrameLayout ?: return
        decorView.findViewWithTag<View>(FAB_TAG)?.let { decorView.removeView(it) }
    }

    // ── Build the FAB view ────────────────────────────────────────────────────

    private fun buildFab(activity: Activity): TextView {
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#BB000000"))   // dark semi-transparent
            setStroke(dp(activity, 1), Color.parseColor("#4400D4FF"))
        }

        return TextView(activity).apply {
            text = ">_"
            textSize = 16f
            setTextColor(Color.parseColor("#00D4FF"))
            typeface = android.graphics.Typeface.MONOSPACE
            gravity = Gravity.CENTER
            background = bg
            elevation = dp(activity, 6).toFloat()

            // Ripple-style press feedback
            isClickable = true
            isFocusable = true
            val ripple = TypedValue()
            activity.theme.resolveAttribute(
                android.R.attr.selectableItemBackgroundBorderless, ripple, true
            )
            // Subtle scale on press
            setOnTouchListener { v, event ->
                when (event.action) {
                    android.view.MotionEvent.ACTION_DOWN ->
                        v.animate().scaleX(0.88f).scaleY(0.88f).setDuration(80).start()
                    android.view.MotionEvent.ACTION_UP,
                    android.view.MotionEvent.ACTION_CANCEL ->
                        v.animate().scaleX(1f).scaleY(1f).setDuration(80).start()
                }
                false   // don't consume — let onClick fire
            }
        }
    }

    private fun dp(activity: Activity, value: Int): Int =
        (value * activity.resources.displayMetrics.density + 0.5f).toInt()
}
