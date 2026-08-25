package io.github.urionsisdi.nfcintime.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.urionsisdi.nfcintime.nfc.ContactUi
import io.github.urionsisdi.nfcintime.time.coarse
import io.github.urionsisdi.nfcintime.ui.theme.Digits
import io.github.urionsisdi.nfcintime.ui.theme.NfcInTimeTheme
import io.github.urionsisdi.nfcintime.ui.theme.Palette

/**
 * The contact, at arm's length. It is the counter screen with a direction added
 * to it — beside the balance in landscape, under it in portrait — and the
 * balance keeps its place either way, because the one thing worth seeing
 * mid-struggle is whether the number is going up or down. Only the winner can
 * read it at all: the phone on top lies screen up, the one underneath faces the
 * floor.
 */
@Composable
fun ContactScreen(state: ContactUi, name: String, modifier: Modifier = Modifier) {
    val words = LocalStrings.current
    val colour = when (state.direction) {
        Direction.TAKING -> Palette.Time
        Direction.LOSING -> Palette.Ink
        Direction.NEUTRAL -> Palette.Dim
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val portrait = maxHeight > maxWidth
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // The gear's place, left empty, so nothing below shifts when a contact starts.
            Spacer(Modifier.height(SettingsMarkHeight))
            Spacer(Modifier.weight(1f))
            Caption("${words.timeLeftOn} $name", color = Palette.Ink)
            Spacer(Modifier.height(26.dp))
            if (portrait) {
                // The counter already fills the width, so the direction goes under it
                // rather than beside it: three lines instead of one, same order.
                Counter(state.balance, Modifier.fillMaxWidth())
                Spacer(Modifier.height(22.dp))
                Arrow(state.direction, colour)
                Spacer(Modifier.height(10.dp))
                Speed(state.ratePerSecond, colour)
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { Counter(state.balance, Modifier.fillMaxWidth()) }
                    Spacer(Modifier.width(20.dp))
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Arrow(state.direction, colour)
                        Spacer(Modifier.height(10.dp))
                        Speed(state.ratePerSecond, colour)
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Rule()
            Spacer(Modifier.height(16.dp))
            Caption("${word(state.direction, words)} ${state.peerName}", color = colour)
        }
    }
}

@Composable
private fun Speed(ratePerSecond: Double, colour: Color) = Text(
    text = coarse(ratePerSecond.toLong(), LocalStrings.current.units),
    style = Digits.copy(fontSize = 26.sp),
    color = colour,
)

/**
 * Drawn rather than typed: the mark belongs to the same blueprint as the phones
 * on the landing, and no font would keep the line weight through it.
 */
@Composable
private fun Arrow(direction: Direction, colour: Color) = Canvas(Modifier.size(52.dp)) {
    val stroke = Stroke(width = size.minDimension * 0.06f, cap = StrokeCap.Square)
    val x = size.width / 2
    if (direction == Direction.NEUTRAL) {
        drawLine(colour, Offset(x - size.width * 0.3f, size.height / 2),
            Offset(x + size.width * 0.3f, size.height / 2), stroke.width, StrokeCap.Square)
        return@Canvas
    }
    val up = direction == Direction.TAKING
    val tip = if (up) size.height * 0.12f else size.height * 0.88f
    val tail = if (up) size.height * 0.88f else size.height * 0.12f
    val wing = if (up) tip + size.height * 0.26f else tip - size.height * 0.26f
    drawLine(colour, Offset(x, tail), Offset(x, tip), stroke.width, StrokeCap.Square)
    drawLine(colour, Offset(x - size.width * 0.2f, wing), Offset(x, tip), stroke.width, StrokeCap.Square)
    drawLine(colour, Offset(x + size.width * 0.2f, wing), Offset(x, tip), stroke.width, StrokeCap.Square)
}

private fun word(direction: Direction, words: Strings) = when (direction) {
    Direction.TAKING -> words.takingFrom
    Direction.LOSING -> words.losingTo
    Direction.NEUTRAL -> words.levelWith
}

@Preview(widthDp = 915, heightDp = 412, backgroundColor = 0xFF000000, showBackground = true)
@Preview(widthDp = 412, heightDp = 915, backgroundColor = 0xFF000000, showBackground = true)
@Composable
private fun ContactPreview() = NfcInTimeTheme {
    ContactScreen(
        state = ContactUi(
            peerName = "sixthday",
            direction = Direction.TAKING,
            ratePerSecond = 3600.0,
            balance = 71 * 31_557_600L,
        ),
        name = "meridian",
    )
}
