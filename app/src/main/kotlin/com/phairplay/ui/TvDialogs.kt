package com.phairplay.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputFilter
import android.text.InputType
import android.view.View
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

    private const val BG_PANEL = 0xF21C1C1E.toInt()
    private const val BG_FOCUS = 0xFF3A5A78.toInt()
    private const val FG_TEXT = 0xFFFFFFFF.toInt()
    private const val FG_DIM = 0xFFBBBBBB.toInt()
    private const val ACCENT = 0xFFFF9500.toInt()

    /** Child 0 is the title, child 1 the divider — rows start at child 2. */
    private const val FIRST_ROW = 2

    /** Dark rounded panel with a white title and an accent divider. */
    private fun panel(context: Context, title: String): LinearLayout {
        val density = context.resources.displayMetrics.density
        val pad = (26 * density).toInt()
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(BG_PANEL)
                cornerRadius = 16f * density
            }
            setPadding(pad, pad, pad, pad)
            addView(TextView(context).apply {
                text = title
                setTextColor(FG_TEXT)
                textSize = 22f
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(View(context).apply {
                setBackgroundColor(ACCENT)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (2 * density).toInt()
                ).apply {
                    topMargin = (14 * density).toInt()
                    bottomMargin = (10 * density).toInt()
                }
            })
        }
    }

    /** A focusable menu row; rounded highlight on focus, dismiss + act on click. */
    private fun actionRow(context: Context, label: String, onClick: (TextView) -> Unit): TextView {
        val density = context.resources.displayMetrics.density
        val v = (14 * density).toInt()
        val h = (10 * density).toInt()
        val radius = 10f * density
        return TextView(context).apply {
            text = label
            setTextColor(FG_TEXT)
            textSize = 20f
            setPadding(v, h, v, h)
            isFocusable = true
            isClickable = true
            setOnFocusChangeListener { _, hasFocus ->
                background = if (hasFocus) {
                    GradientDrawable().apply {
                        setColor(BG_FOCUS)
                        cornerRadius = radius
                    }
                } else {
                    null
                }
            }
            setOnClickListener { view -> onClick(view as TextView) }
        }
    }

    private fun dismissByTag(view: TextView) {
        (view.tag as? Dialog)?.dismiss()
    }

    /** Builds + shows the dialog; stashes it on every child so rows can dismiss. */
    private fun show(context: Context, container: LinearLayout, focusChildIndex: Int = FIRST_ROW): Dialog {
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
     *
     * [checkedIndex] (v105) marks the row that is currently in effect with a
     * leading ✓ and parks the focus on it. A TV remote has no pointer, so a
     * menu that only lists options forces the user to open it to learn what is
     * selected; this is what the speed / aspect / track menus now rely on.
     */
    fun menu(
        context: Context,
        title: String,
        entries: List<Pair<String, () -> Unit>>,
        checkedIndex: Int = -1
    ): Dialog {
        val container = panel(context, title)
        entries.forEachIndexed { index, (label, action) ->
            val shown = if (index == checkedIndex) "✓  $label" else label
            container.addView(actionRow(context, shown) { rowView ->
                dismissByTag(rowView)
                action()
            })
        }
        val focusIndex = if (checkedIndex in entries.indices) FIRST_ROW + checkedIndex else FIRST_ROW
        return show(context, container, focusIndex)
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
        // The edit field is the first child after the title + divider.
        return show(context, container, focusChildIndex = FIRST_ROW)
    }
}
