package io.github.urionsisdi.nfcintime.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier

/**
 * There is no Material surface here and no colour scheme to speak of: the app is
 * black, and the three colours it does use carry meaning rather than hierarchy.
 */
@Composable
fun NfcInTimeTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalContentColor provides Palette.Ink,
        LocalTextStyle provides Body,
    ) {
        Box(Modifier.fillMaxSize().background(Palette.Bg)) { content() }
    }
}
