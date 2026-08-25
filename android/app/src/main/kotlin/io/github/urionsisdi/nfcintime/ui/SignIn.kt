package io.github.urionsisdi.nfcintime.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateValue
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.urionsisdi.nfcintime.ui.theme.NfcInTimeTheme
import io.github.urionsisdi.nfcintime.ui.theme.Palette

/**
 * The gate. Twenty-five years are issued once per Telegram account, so nothing can
 * start before the account is proved. Wordmark, terms, rule, door — in that
 * order, the way the landing's splash sets it.
 */
@Composable
fun SignInScreen(
    working: Boolean,
    error: String?,
    onSignIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val words = LocalStrings.current
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Heading("nfc in time")
        Spacer(Modifier.height(18.dp))
        Caption(words.once, color = Palette.Dim)
        Spacer(Modifier.height(30.dp))
        Track(working)
        Spacer(Modifier.height(30.dp))
        if (error != null) {
            Caption(error, color = Palette.Alarm)
            Spacer(Modifier.height(20.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Action(
                text = if (working) words.signingIn else words.signIn,
                onClick = { if (!working) onSignIn() },
                color = if (working) Palette.Muted else Palette.Time,
            )
            if (working) {
                Spacer(Modifier.width(10.dp))
                Ellipsis()
            }
        }
    }
}

/**
 * Telegram takes its seconds to answer, and they are long ones. The bar and the
 * dots exist only to say the wait is the app's doing and not a hang.
 */
@Composable
private fun Track(working: Boolean) {
    val width = 140.dp
    val run = if (working) {
        rememberInfiniteTransition(label = "track").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                tween(1_400, easing = LinearEasing),
                RepeatMode.Restart,
            ),
            label = "run",
        ).value
    } else {
        0f
    }
    Box(Modifier.width(width).height(1.dp).background(Palette.Rule)) {
        if (working) {
            val travel = width * 0.6f
            Box(
                Modifier
                    .offset(x = travel * run)
                    .fillMaxWidth(0.4f)
                    .fillMaxSize()
                    .background(Palette.Time),
            )
        }
    }
}

/** Three squares lit in turn — dots at the line weight of everything else. */
@Composable
private fun Ellipsis() {
    val lit = rememberInfiniteTransition(label = "ellipsis").animateValue(
        initialValue = 0,
        targetValue = DOTS + 1,
        typeConverter = Int.VectorConverter,
        animationSpec = infiniteRepeatable(
            tween(DOTS_PERIOD_MILLIS, easing = LinearEasing),
            RepeatMode.Restart,
        ),
        label = "lit",
    ).value
    Canvas(Modifier.size(width = 26.dp, height = 4.dp)) {
        val step = size.width / DOTS
        val side = size.height
        repeat(DOTS) { i ->
            drawRect(
                color = if (i < lit) Palette.Time else Palette.Dim,
                topLeft = Offset(i * step, 0f),
                size = Size(side, side),
            )
        }
    }
}

private const val DOTS = 3
private const val DOTS_PERIOD_MILLIS = 1_200

@Preview(widthDp = 915, heightDp = 412, backgroundColor = 0xFF000000, showBackground = true)
@Composable
private fun SignInPreview() = NfcInTimeTheme {
    SignInScreen(working = false, error = null, onSignIn = {})
}
