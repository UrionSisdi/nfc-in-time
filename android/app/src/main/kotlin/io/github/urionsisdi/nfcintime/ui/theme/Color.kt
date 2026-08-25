package io.github.urionsisdi.nfcintime.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The landing's palette, unchanged: white is a physical object, grey is
 * supporting information, green is time and only time. Pure black on purpose —
 * anything tinted seams against an animated layer.
 *
 * [Alarm] is the app's one addition: the landing never has to say that something
 * went wrong, and grey said it so quietly that a failed sign-in read as a hint.
 * It is the green mirrored across the neutral axis, so it belongs to the same set.
 */
object Palette {
    val Bg = Color(0xFF000000)
    val Ink = Color(0xFFEDEDEB)
    val Muted = Color(0xFFCBCDC9)
    val Dim = Color(0xFF6E736F)
    val Time = Color(0xFFA8D493)
    val TimeDim = Color(0xFF8CB57B)
    val Alarm = Color(0xFFD48A7A)
    val Rule = Color(0x21C8CDC8)
}
