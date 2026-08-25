package io.github.urionsisdi.nfcintime.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.urionsisdi.nfcintime.ui.theme.Digits
import io.github.urionsisdi.nfcintime.ui.theme.NfcInTimeTheme
import io.github.urionsisdi.nfcintime.ui.theme.Palette
import io.github.urionsisdi.nfcintime.time.group
import io.github.urionsisdi.nfcintime.time.span

/**
 * The counter itself: five fields, the way the landing sets them. The digits are
 * sized off the width actually given to the row — not the screen — so a display
 * cutout narrows the type instead of clipping it, and portrait gets the same
 * five fields in one line rather than a second layout to keep in step.
 *
 * The captions shrink with the digits: they are set at a fixed 12sp beside
 * landscape's 83sp, and at portrait's half of that they would be the widest
 * thing in the field and push the row past the screen.
 */
@Composable
fun Counter(seconds: Long, modifier: Modifier = Modifier, scale: Float = 1f) {
    val words = LocalStrings.current
    val left = span(seconds)
    BoxWithConstraints(modifier) {
        val size = (maxWidth.value / 11f * scale).toInt()
        val caption = (size * CAPTION_RATIO).coerceIn(CAPTION_MIN, CAPTION_MAX).sp
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.Top,
        ) {
            Field(group(left.years), words.years, size, caption)
            Separator(size)
            Field(left.days.toString(), words.days, size, caption)
            Separator(size)
            Field(pad(left.hours), words.hours, size, caption)
            Separator(size)
            Field(pad(left.minutes), words.minutes, size, caption)
            Separator(size)
            Field(pad(left.seconds), words.seconds, size, caption)
        }
    }
}

@Composable
private fun Field(value: String, caption: String, size: Int, captionSize: TextUnit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = Digits.copy(fontSize = size.sp),
            color = Palette.Time,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height((size / 6).dp))
        Caption(caption, color = Palette.TimeDim, size = captionSize)
    }
}

@Composable
private fun Separator(size: Int) = Text(
    text = ":",
    style = Digits.copy(fontSize = (size * 0.78).sp),
    color = Palette.Time,
    modifier = Modifier.padding(horizontal = (size / 12).dp),
)

/** The main screen: what is left, and the one thing you can do about it. */
@Composable
fun CounterScreen(
    seconds: Long,
    name: String,
    hint: String,
    signal: Signal,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            SettingsMark(onSettings)
        }
        Spacer(Modifier.weight(1f))
        Caption("${LocalStrings.current.timeLeftOn} $name", color = Palette.Ink)
        Spacer(Modifier.height(26.dp))
        Counter(seconds, Modifier.fillMaxWidth())
        Spacer(Modifier.weight(1f))
        Rule()
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SignalMark(signal)
            Spacer(Modifier.width(9.dp))
            Caption(hint, color = if (signal == Signal.READY) Palette.Muted else Palette.Alarm)
        }
    }
}

private const val CAPTION_RATIO = 0.3f
private const val CAPTION_MIN = 8f
private const val CAPTION_MAX = 12f

private fun pad(value: Long): String = if (value < 10) "0$value" else value.toString()

@Preview(widthDp = 915, heightDp = 412, backgroundColor = 0xFF000000, showBackground = true)
@Preview(widthDp = 412, heightDp = 915, backgroundColor = 0xFF000000, showBackground = true)
@Composable
private fun CounterPreview() = NfcInTimeTheme {
    CounterScreen(
        seconds = 99 * 31_557_600L + 364 * 86_400L + 3599,
        name = "meridian",
        hint = "touch another phone",
        signal = Signal.READY,
        onSettings = {},
    )
}
