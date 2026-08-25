package io.github.urionsisdi.nfcintime.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.urionsisdi.nfcintime.BuildConfig
import io.github.urionsisdi.nfcintime.update.UpdateState
import io.github.urionsisdi.nfcintime.ui.theme.Body
import io.github.urionsisdi.nfcintime.ui.theme.NfcInTimeTheme
import io.github.urionsisdi.nfcintime.ui.theme.Palette

/**
 * Three lines: the name, which is a label on the public board and nothing more —
 * the player is the Telegram account, and it comes from there by default — the
 * language, and the version, which is also the way an update is installed.
 */
@Composable
fun SettingsScreen(
    name: String,
    lang: Lang,
    update: UpdateState,
    onSave: (String) -> Unit,
    onLanguage: (Lang) -> Unit,
    onUpdate: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val words = LocalStrings.current
    var typed by remember(name) { mutableStateOf(name) }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 18.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Caption(words.settings, color = Palette.Dim)
            Action(words.done, { onSave(typed); onBack() })
        }

        Spacer(Modifier.height(48.dp))
        Caption(words.name, color = Palette.Dim)
        Spacer(Modifier.height(14.dp))
        BasicTextField(
            value = typed,
            onValueChange = { typed = it.take(32) },
            singleLine = true,
            textStyle = Body.copy(color = Palette.Ink),
            cursorBrush = SolidColor(Palette.Time),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(14.dp))
        Rule()

        Spacer(Modifier.height(40.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Caption(words.language, color = Palette.Dim)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Lang.entries.forEachIndexed { i, option ->
                    if (i > 0) Caption(" / ", color = Palette.Dim)
                    Action(
                        text = option.code,
                        onClick = { onLanguage(option) },
                        color = if (option == lang) Palette.Time else Palette.Dim,
                    )
                }
            }
        }

        Spacer(Modifier.height(28.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Caption(words.update, color = Palette.Dim)
            Action(text = words.say(update), onClick = onUpdate, color = update.tint())
        }
    }
}

/**
 * The line reads as what it is right now: the installed version until asked,
 * then the answer. Only an offer to install is coloured as something to do.
 */
private fun Strings.say(update: UpdateState): String = when (update) {
    is UpdateState.Idle -> BuildConfig.VERSION_NAME
    is UpdateState.Checking -> checking
    is UpdateState.Current -> upToDate
    is UpdateState.Ready -> "${update.versionName} · $install"
    is UpdateState.Downloading -> "${update.percent}%"
    is UpdateState.NeedsPermission -> allowInstall
    is UpdateState.Failed -> updateFailed
}

private fun UpdateState.tint(): Color = when (this) {
    is UpdateState.Ready, is UpdateState.Downloading -> Palette.Time
    is UpdateState.NeedsPermission, is UpdateState.Failed -> Palette.Alarm
    else -> Palette.Dim
}

@Preview(widthDp = 915, heightDp = 412, backgroundColor = 0xFF000000, showBackground = true)
@Composable
private fun SettingsPreview() = NfcInTimeTheme {
    SettingsScreen(
        name = "meridian",
        lang = Lang.EN,
        update = UpdateState.Ready("0.2.0"),
        onSave = {},
        onLanguage = {},
        onUpdate = {},
        onBack = {},
    )
}
