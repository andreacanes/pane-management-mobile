package com.andreacanes.panemgmt.ui.detail

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight

// ---------------------------------------------------------------------------
// 256-color palette (standard terminal xterm-256color)
// ---------------------------------------------------------------------------

private val PALETTE: Array<Color> = buildPalette()

private fun buildPalette(): Array<Color> {
    val p = Array(256) { Color.White }

    // 0-7: standard colours
    p[0] = Color(0xFF000000)  // black
    p[1] = Color(0xFFCD0000)  // red
    p[2] = Color(0xFF00CD00)  // green
    p[3] = Color(0xFFCDCD00)  // yellow
    p[4] = Color(0xFF0000EE)  // blue
    p[5] = Color(0xFFCD00CD)  // magenta
    p[6] = Color(0xFF00CDCD)  // cyan
    p[7] = Color(0xFFE5E5E5)  // white

    // 8-15: bright variants
    p[8]  = Color(0xFF7F7F7F)  // bright black (grey)
    p[9]  = Color(0xFFFF0000)  // bright red
    p[10] = Color(0xFF00FF00)  // bright green
    p[11] = Color(0xFFFFFF00)  // bright yellow
    p[12] = Color(0xFF5C5CFF)  // bright blue
    p[13] = Color(0xFFFF00FF)  // bright magenta
    p[14] = Color(0xFF00FFFF)  // bright cyan
    p[15] = Color(0xFFFFFFFF)  // bright white

    // 16-231: 6x6x6 colour cube
    val levels = intArrayOf(0x00, 0x5F, 0x87, 0xAF, 0xD7, 0xFF)
    for (r in 0..5) for (g in 0..5) for (b in 0..5) {
        p[16 + 36 * r + 6 * g + b] = Color(
            red = levels[r],
            green = levels[g],
            blue = levels[b],
            alpha = 0xFF,
        )
    }

    // 232-255: greyscale ramp 0x08..0xEE step 10
    for (i in 0..23) {
        val v = 0x08 + i * 10
        p[232 + i] = Color(red = v, green = v, blue = v, alpha = 0xFF)
    }

    return p
}

// ---------------------------------------------------------------------------
// ANSI state tracking
// ---------------------------------------------------------------------------

private data class AnsiState(
    val fgColor: Color? = null,
    val bold: Boolean = false,
    val dim: Boolean = false,
    val italic: Boolean = false,
)

private fun AnsiState.toSpanStyle(defaultColor: Color): SpanStyle = SpanStyle(
    color = when {
        dim && fgColor != null -> fgColor.copy(alpha = 0.6f)
        dim -> defaultColor.copy(alpha = 0.6f)
        fgColor != null -> fgColor
        else -> Color.Unspecified
    },
    fontWeight = if (bold) FontWeight.Bold else null,
    fontStyle = if (italic) FontStyle.Italic else null,
)

// ---------------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------------

/**
 * Parse a single line of text containing ANSI CSI escape sequences and
 * return a styled [AnnotatedString]. Only SGR (Select Graphic Rendition,
 * final byte `m`) sequences are interpreted; all other CSI sequences are
 * silently consumed and dropped.
 *
 * [defaultColor] is the baseline text colour (typically `onSurface`).
 */
fun parseAnsiLine(raw: String, defaultColor: Color): AnnotatedString = buildAnnotatedString {
    var state = AnsiState()
    var i = 0
    val len = raw.length

    // Start with default style
    pushStyle(state.toSpanStyle(defaultColor))

    while (i < len) {
        val c = raw[i]
        if (c == '\u001B' && i + 1 < len && raw[i + 1] == '[') {
            // CSI sequence: consume \x1b[ ... <final byte>
            i += 2 // skip ESC [
            val paramStart = i
            // Consume parameter bytes (0x30-0x3F) and intermediate bytes (0x20-0x2F)
            while (i < len && raw[i].code in 0x20..0x3F) i++
            val finalByte = if (i < len) raw[i] else break
            val paramStr = raw.substring(paramStart, i)
            i++ // skip final byte

            if (finalByte == 'm') {
                // SGR sequence — parse parameters
                state = applySgr(paramStr, state)
                pop() // remove previous style
                pushStyle(state.toSpanStyle(defaultColor))
            }
            // All other CSI sequences (cursor movement, etc.) are silently dropped
        } else if (c == '\u001B' && i + 1 < len && raw[i + 1] == ']') {
            // OSC sequence: \x1b] ... (terminated by ST = \x1b\\ or BEL = \x07)
            // Used for hyperlinks (OSC 8), window titles, etc. Consume entirely.
            i += 2
            while (i < len) {
                if (raw[i] == '\u0007') { i++; break }                         // BEL terminator
                if (raw[i] == '\u001B' && i + 1 < len && raw[i + 1] == '\\') { i += 2; break } // ST terminator
                i++
            }
        } else if (c == '\r') {
            // Strip carriage returns
            i++
        } else {
            append(c)
            i++
        }
    }
    pop()
}

/**
 * Strip all ANSI CSI escape sequences from [s], returning plain text.
 * Same algorithm as [parseAnsiLine] but without building styled output.
 */
fun stripAnsi(s: String): String {
    val out = StringBuilder(s.length)
    var i = 0
    val len = s.length
    while (i < len) {
        val c = s[i]
        if (c == '\u001B' && i + 1 < len && s[i + 1] == '[') {
            // CSI sequence
            i += 2
            while (i < len && s[i].code in 0x20..0x3F) i++
            if (i < len) i++ // skip final byte
        } else if (c == '\u001B' && i + 1 < len && s[i + 1] == ']') {
            // OSC sequence (hyperlinks, window title, etc.)
            i += 2
            while (i < len) {
                if (s[i] == '\u0007') { i++; break }
                if (s[i] == '\u001B' && i + 1 < len && s[i + 1] == '\\') { i += 2; break }
                i++
            }
        } else if (c != '\r') {
            out.append(c)
            i++
        } else {
            i++
        }
    }
    return out.toString()
}

// ---------------------------------------------------------------------------
// SGR parameter parser
// ---------------------------------------------------------------------------

private fun applySgr(paramStr: String, current: AnsiState): AnsiState {
    if (paramStr.isEmpty()) return AnsiState() // bare ESC[m = reset

    val params = paramStr.split(';').mapNotNull { it.toIntOrNull() }
    var state = current
    var idx = 0

    while (idx < params.size) {
        when (val code = params[idx]) {
            0 -> state = AnsiState() // reset
            1 -> state = state.copy(bold = true)
            2 -> state = state.copy(dim = true)
            3 -> state = state.copy(italic = true)
            22 -> state = state.copy(bold = false, dim = false)
            23 -> state = state.copy(italic = false)

            in 30..37 -> state = state.copy(fgColor = PALETTE[code - 30])
            39 -> state = state.copy(fgColor = null) // default fg

            in 90..97 -> state = state.copy(fgColor = PALETTE[code - 90 + 8])

            38 -> {
                // Extended foreground: 38;5;N (256-color) or 38;2;R;G;B (truecolor)
                if (idx + 1 < params.size) {
                    when (params[idx + 1]) {
                        5 -> {
                            // 256-color: 38;5;N
                            if (idx + 2 < params.size) {
                                val n = params[idx + 2].coerceIn(0, 255)
                                state = state.copy(fgColor = PALETTE[n])
                                idx += 2
                            }
                        }
                        2 -> {
                            // Truecolor: 38;2;R;G;B
                            if (idx + 4 < params.size) {
                                val r = params[idx + 2].coerceIn(0, 255)
                                val g = params[idx + 3].coerceIn(0, 255)
                                val b = params[idx + 4].coerceIn(0, 255)
                                state = state.copy(fgColor = Color(r, g, b, 0xFF))
                                idx += 4
                            }
                        }
                    }
                }
            }

            // Background colours (40-47, 48;5;N, 48;2;R;G;B, 49, 100-107)
            // are intentionally ignored — terminal background rendering on a
            // phone chat view would look wrong against the app's dark surface.
            // We consume the parameters so they don't misparse as foreground.
            48 -> {
                if (idx + 1 < params.size) {
                    when (params[idx + 1]) {
                        5 -> idx += 2    // skip 48;5;N
                        2 -> idx += 4    // skip 48;2;R;G;B
                    }
                }
            }
        }
        idx++
    }
    return state
}
