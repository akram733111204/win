package com.rukn.expiry.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

internal object Palette {
    val navy = Color.rgb(14, 30, 48)
    val navy2 = Color.rgb(27, 56, 70)
    val green = Color.rgb(21, 153, 116)
    val greenLight = Color.rgb(222, 249, 239)
    val white = Color.WHITE
    val canvas = Color.rgb(245, 248, 248)
    val grey = Color.rgb(111, 127, 138)
    val border = Color.rgb(225, 235, 234)
    val orange = Color.rgb(212, 128, 32)
    val red = Color.rgb(204, 68, 72)
}

internal fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

internal fun rounded(color: Int, radius: Float = 18f, stroke: Int? = null): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
        if (stroke != null) setStroke(1, stroke)
    }

internal fun Context.label(
    content: String,
    sp: Float = 15f,
    color: Int = Palette.navy,
    bold: Boolean = false
): TextView = TextView(this).apply {
    text = content
    textSize = sp
    setTextColor(color)
    gravity = Gravity.CENTER_VERTICAL
    if (bold) setTypeface(null, Typeface.BOLD)
    includeFontPadding = true
}

internal fun Context.action(text: String, light: Boolean = false, onClick: () -> Unit): TextView =
    label(text, 14f, if (light) Palette.green else Palette.white, true).apply {
        gravity = Gravity.CENTER
        setPadding(dp(15), dp(13), dp(15), dp(13))
        background = rounded(if (light) Palette.greenLight else Palette.green, dp(14).toFloat())
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

internal fun Context.vertical(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    layoutDirection = View.LAYOUT_DIRECTION_RTL
}

internal fun Context.horizontal(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    layoutDirection = View.LAYOUT_DIRECTION_RTL
    gravity = Gravity.CENTER_VERTICAL
}

internal fun Context.card(): LinearLayout = vertical().apply {
    setPadding(dp(18), dp(18), dp(18), dp(18))
    background = rounded(Palette.white, dp(20).toFloat(), Palette.border)
}

internal fun Context.columnSpace(dpSize: Int): View = View(this).apply {
    layoutParams = LinearLayout.LayoutParams(1, dp(dpSize))
}

internal fun Context.rowSpace(dpSize: Int): View = View(this).apply {
    layoutParams = LinearLayout.LayoutParams(dp(dpSize), 1)
}
