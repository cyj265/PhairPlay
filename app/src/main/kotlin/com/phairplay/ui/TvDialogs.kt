package com.phairplay.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Typeface
import android.text.InputFilter
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * TvDialogs — TV-safe dialogs drawn by hand instead of AlertDialog.
 *
 * WHY: The leanback theme's alert-dialog chrome (title, list items, buttons)
 * rendered with no visible text on the N1 — the user saw an empty striped box
 * (fixed for the player menus in v62, but the Settings dialogs still used
 * AlertDialog and carried the same latent bug). Explicit colors/sizes here
 * cannot be broken by any theme. All styles live in ONE place so the player
 * menu, the settings dialogs and any future dialog stay visually identical.
 *
 * HOW: Plain LinearLayout in a borderless Dialog. Rows are focusable TextViews
 * (white on dark, blue tint on focus); Back dismisses. Each row stashes its
 * Dialog in the view tag so a click can dismiss it before running its action.
 */
object TvDialogs {

    private const val BG_PANEL = 0xF2101010.toInt()
    private const val BG_FOCUS = 0xFF3A5A78.toInt()
    private const val FG_TEXT = 0xFFFFFFFF.toInt()
    private const val FG_DIM = 0xFFBBBBBB.toInt()

    /** Dark panel with a white title; rows are appended by the callers. */
    private fun panel(context: Context, title: String): LinearLayout {
        val density = context.resources.displayMetrics.density
        val pad = (24 * density).toInt()
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG_PANEL)
            setPadding(pad, pad, pad, pad)
            addView(TextView(context).apply {
                text = title
                setTextColor(FG_TEXT)
                textSize = 22f
                setPadding(0, 0, 0, pad)
            })
        }
    }

    /** A focusable menu row; highlight on focus, dismiss + act on click. */
    private fun actionRow(context: Context, label: String, onClick: (TextView) -> Unit): TextView {
        val density = context.resources.displayMetrics.density
        val v = (10 * density).toInt()
        return TextView(context).apply {
            text = label
            setTextColor(FG_TEXT)
            textSize = 20f
            setPadding(v, v, v, v)
            isFocusable = true
            isClickable = true
            setOnFocusChangeListener { _, hasFocus ->
                setBackgroundColor(if (hasFocus) BG_FOCUS else 0x00000000)
            }
            setOnClickListener { view -> onClick(view as TextView) }
        }
    }

    private fun dismissByTag(view: TextView) {
        (view.tag as? Dialog)?.dismiss()
    }

    /** Builds + shows the dialog; stashes it on every child so rows can dismiss. */
    private fun show(context: Context, container: LinearLayout, focusChildIndex: Int = 1): Dialog {
        val dialog = Dialog(context).apply {
            requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
            setContentView(container)
            setCancelable(true)
            window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0x00000000))
        }
        for (i in 0 until container.childCount) {
            container.getChildAt(i).tag = dialog
        }
        if (container.childCount > focusChildIndex) {
            container.getChildAt(focusChildIndex).requestFocus()
        }
        dialog.show()
        return dialog
    }

    /**
     * A hand-drawn option menu — the direct replacement for AlertDialog
     * setItems(). Entries are (label, action) pairs.
     */
    fun menu(context: Context, title: String, entries: List<Pair<String, () -> Unit>>): Dialog {
        val container = panel(context, title)
        for ((label, action) in entries) {
            container.addView(actionRow(context, label) { rowView ->
                dismissByTag(rowView)
                action()
            })
        }
        return show(context, container)
    }

    /**
     * A text panel (debug dump etc.) with optional action rows and a Close
     * row. [textProvider] is re-read every 2 s while shown so live counters
     * update without forcing a close/reopen roundtrip on a TV remote.
     */
    fun info(
        context: Context,
        title: String,
        textProvider: () -> String,
        actions: List<Pair<String, () -> Unit>> = emptyList()
    ): Dialog {
        val container = panel(context, title)
        val density = context.resources.displayMetrics.density
        val pad = (12 * density).toInt()
        val text = TextView(context).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(FG_TEXT)
            setBackgroundColor(0xFF111111.toInt())
            setPadding(pad, pad, pad, pad)
        }
        container.addView(ScrollView(context).apply { addView(text) })
        for ((label, action) in actions) {
            container.addView(actionRow(context, label) { rowView ->
                dismissByTag(rowView)
                action()
            })
        }
        container.addView(actionRow(context, "关闭") { rowView -> dismissByTag(rowView) })
        val dialog = show(context, container)
        // Live refresh while shown (a snapshot goes stale the moment counters move).
        val refresh = object : Runnable {
            override fun run() {
                if (!dialog.isShowing) return
                text.text = textProvider()
                text.postDelayed(this, 2000)
            }
        }
        text.post(refresh)
        return dialog
    }

    /**
     * A single-line text input (display name etc.) with Confirm / optional
     * Reset / Cancel rows — the hand-drawn replacement for AlertDialog
     * setView(editText), whose title and buttons were invisible on the N1.
     */
    fun input(
        context: Context,
        title: String,
        prefill: String,
        hint: String,
        maxLength: Int,
        onConfirm: (String) -> Unit,
        onReset: (() -> Unit)? = null
    ): Dialog {
        val container = panel(context, title)
        val density = context.resources.displayMetrics.density
        val pad = (12 * density).toInt()
        val editText = EditText(context).apply {
            setText(prefill)
            setTextColor(FG_TEXT)
            setHintTextColor(FG_DIM)
            setHint(hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(InputFilter.LengthFilter(maxLength))
            setSingleLine(true)
            setBackgroundColor(0xFF1A1A1A.toInt())
            setPadding(pad, pad, pad, pad)
            setSelection(prefill.length)
        }
        container.addView(editText)
        container.addView(actionRow(context, "确定") { rowView ->
            val value = editText.text?.toString()?.trim() ?: ""
            dismissByTag(rowView)
            onConfirm(value)
        })
        if (onReset != null) {
            container.addView(actionRow(context, "恢复默认") { rowView ->
                dismissByTag(rowView)
                onReset()
            })
        }
        container.addView(actionRow(context, "取消") { rowView -> dismissByTag(rowView) })
        return show(context, container, focusChildIndex = 0)
    }
}
