package com.example.wificoverage

import android.graphics.Color

fun rssiToColor(rssi: Int): Int {
    val t = ((rssi + 90) / 60f).coerceIn(0f, 1f)
    val hue = t * 120f
    return Color.HSVToColor(180, floatArrayOf(hue, 1f, 0.9f))
}
