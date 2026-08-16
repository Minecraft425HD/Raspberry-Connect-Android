package com.raspberryconnect.terminal.terminal

/**
 * Pure ARGB int palette (no android.graphics.Color dependency, so it can be unit
 * tested and reused by the rendering layer without touching the Android runtime).
 */
object TerminalColors {

    private val ansi16 = intArrayOf(
        0xFF000000.toInt(), 0xFFCD3131.toInt(), 0xFF0DBC79.toInt(), 0xFFE5E510.toInt(),
        0xFF2472C8.toInt(), 0xFFBC3FBC.toInt(), 0xFF11A8CD.toInt(), 0xFFE5E5E5.toInt(),
        0xFF666666.toInt(), 0xFFF14C4C.toInt(), 0xFF23D18B.toInt(), 0xFFF5F543.toInt(),
        0xFF3B8EEA.toInt(), 0xFFD670D6.toInt(), 0xFF29B8DB.toInt(), 0xFFFFFFFF.toInt()
    )

    private val cube = IntArray(216).also { arr ->
        val steps = intArrayOf(0, 95, 135, 175, 215, 255)
        var i = 0
        for (r in steps) for (g in steps) for (b in steps) {
            arr[i++] = argb(r, g, b)
        }
    }

    private val grayscale = IntArray(24).also { arr ->
        for (i in arr.indices) {
            val level = 8 + i * 10
            arr[i] = argb(level, level, level)
        }
    }

    /** Resolves a 0-255 palette index (as used by SGR 38;5;n / 48;5;n) to ARGB. */
    fun paletteColor(index: Int): Int = when {
        index < 16 -> ansi16[index.coerceIn(0, 15)]
        index < 232 -> cube[(index - 16).coerceIn(0, 215)]
        else -> grayscale[(index - 232).coerceIn(0, 23)]
    }

    /** Maps a 24-bit truecolor SGR request onto the nearest 256-color palette entry. */
    fun nearestPaletteIndex(r: Int, g: Int, b: Int): Int {
        var best = 0
        var bestDist = Int.MAX_VALUE
        for (i in 0..255) {
            val c = paletteColor(i)
            val dr = ((c shr 16) and 0xFF) - r
            val dg = ((c shr 8) and 0xFF) - g
            val db = (c and 0xFF) - b
            val dist = dr * dr + dg * dg + db * db
            if (dist < bestDist) {
                bestDist = dist
                best = i
            }
        }
        return best
    }

    private fun argb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}
