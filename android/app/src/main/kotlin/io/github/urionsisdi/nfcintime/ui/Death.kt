package io.github.urionsisdi.nfcintime.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.urionsisdi.nfcintime.time.coarse
import io.github.urionsisdi.nfcintime.ui.theme.NfcInTimeTheme
import io.github.urionsisdi.nfcintime.ui.theme.Palette

/**
 * Zero. The account keeps the death, so reinstalling brings it right back — that
 * is the whole point of hanging the balance on the Telegram id.
 */
@Composable
fun DeathScreen(livedSeconds: Long, modifier: Modifier = Modifier) {
    val words = LocalStrings.current
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Heading(words.zeroed, color = Palette.Muted)
        Spacer(Modifier.height(36.dp))
        Rule()
        Spacer(Modifier.height(28.dp))
        Caption(words.lived, color = Palette.Dim)
        Spacer(Modifier.height(12.dp))
        Caption(coarse(livedSeconds, words.units), color = Palette.Muted)
    }
}

@Preview(widthDp = 915, heightDp = 412, backgroundColor = 0xFF000000, showBackground = true)
@Composable
private fun DeathPreview() = NfcInTimeTheme {
    DeathScreen(livedSeconds = 41 * 31_557_600L + 12 * 86_400L)
}
