package io.github.urionsisdi.nfcintime.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import io.github.urionsisdi.nfcintime.ui.theme.Label
import io.github.urionsisdi.nfcintime.ui.theme.Palette
import io.github.urionsisdi.nfcintime.ui.theme.Title

@Composable
fun Caption(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Palette.Muted,
    size: TextUnit = Label.fontSize,
) = Text(
    text = text.uppercase(),
    style = Label.copy(fontSize = size),
    color = color,
    textAlign = TextAlign.Center,
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
    modifier = modifier,
)

@Composable
fun Heading(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Palette.Ink,
) = Text(
    text = text.uppercase(),
    style = Title,
    color = color,
    textAlign = TextAlign.Center,
    modifier = modifier,
)

/** The hairline the landing separates everything with. */
@Composable
fun Rule(modifier: Modifier = Modifier) =
    Box(modifier.fillMaxWidth().height(1.dp).background(Palette.Rule))

private val MarkSize = 24.dp
private val MarkPadding = 12.dp

/** The gear's full height, so a screen without one can leave its place empty. */
val SettingsMarkHeight = MarkSize + MarkPadding * 2

/**
 * A gear at the line weight of a blueprint: a thin ring, a hub, and eight teeth
 * struck outward. Drawn rather than set in an icon font so it keeps the stroke
 * of everything else on the screen.
 */
@Composable
fun SettingsMark(onClick: () -> Unit, modifier: Modifier = Modifier) =
    Canvas(
        modifier
            .clickable(onClick = onClick)
            .padding(vertical = MarkPadding)
            .size(MarkSize),
    ) {
        val stroke = size.minDimension * 0.055f
        val centre = Offset(size.width / 2, size.height / 2)
        val ring = size.minDimension * 0.30f
        drawCircle(Palette.Muted, ring, centre, style = Stroke(stroke))
        drawCircle(Palette.Dim, size.minDimension * 0.10f, centre, style = Stroke(stroke))
        repeat(8) { i ->
            val angle = i * PI.toFloat() / 4f
            val dx = cos(angle)
            val dy = sin(angle)
            drawLine(
                Palette.Muted,
                Offset(centre.x + dx * ring, centre.y + dy * ring),
                Offset(centre.x + dx * size.minDimension * 0.46f, centre.y + dy * size.minDimension * 0.46f),
                stroke,
                StrokeCap.Square,
            )
        }
    }

/** What the line at the bottom of the counter is saying. */
enum class Signal { READY, NO_NFC, TROUBLE }

/**
 * The mark beside that line: two arcs of a field leaving the phone, struck
 * through when the radio is off, and a single square when the trouble is the
 * server rather than the radio. Drawn, like the gear and the arrow.
 */
@Composable
fun SignalMark(signal: Signal, modifier: Modifier = Modifier) {
    val colour = if (signal == Signal.READY) Palette.Muted else Palette.Alarm
    Canvas(modifier.size(MarkGlyph)) {
        val stroke = size.minDimension * 0.09f
        if (signal == Signal.TROUBLE) {
            val side = size.minDimension * 0.42f
            drawRect(
                colour,
                Offset((size.width - side) / 2, (size.height - side) / 2),
                Size(side, side),
            )
            return@Canvas
        }
        // Two arcs of one field, struck about a centre off the left edge so both
        // open to the right — the phone is the source, the field leaves it.
        val centre = Offset(size.width * 0.04f, size.height / 2)
        listOf(0.44f, 0.72f).forEach { fraction ->
            val radius = size.width * fraction
            drawArc(
                color = colour,
                startAngle = -52f,
                sweepAngle = 104f,
                useCenter = false,
                topLeft = Offset(centre.x - radius, centre.y - radius),
                size = Size(radius * 2, radius * 2),
                style = Stroke(width = stroke, cap = StrokeCap.Square),
            )
        }
        if (signal == Signal.NO_NFC) {
            drawLine(
                colour,
                Offset(size.width * 0.08f, size.height * 0.88f),
                Offset(size.width * 0.92f, size.height * 0.12f),
                stroke,
                StrokeCap.Square,
            )
        }
    }
}

private val MarkGlyph = 13.dp

/**
 * The only kind of button in the app: a word, tracked out, that changes colour
 * when it is the thing you can do next.
 */
@Composable
fun Action(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = Palette.Time,
) = Caption(
    text = text,
    color = color,
    // Vertical padding only: the tap target grows downward, the word still lines
    // up with the label across from it and with the edge of the screen.
    modifier = modifier.clickable(onClick = onClick).padding(vertical = 12.dp),
)
