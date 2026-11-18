package ru.dimlife.radio

import android.os.Build
import android.text.Html

fun decodeMetadata(value: String): String {
    if (value.isBlank()) return value
    var decoded = value.trim()
    repeat(3) {
        val next = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Html.fromHtml(decoded, Html.FROM_HTML_MODE_LEGACY).toString().trim()
        } else {
            @Suppress("DEPRECATION")
            Html.fromHtml(decoded).toString().trim()
        }
        if (next == decoded) return decoded
        decoded = next
    }
    return decoded
}
