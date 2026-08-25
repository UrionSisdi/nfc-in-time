package io.github.urionsisdi.nfcintime.ui.theme

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.urionsisdi.nfcintime.R

val Mono = FontFamily(
    Font(R.font.ibm_plex_mono_extralight, FontWeight.ExtraLight),
    Font(R.font.ibm_plex_mono_light, FontWeight.Light),
    Font(R.font.ibm_plex_mono_regular, FontWeight.Normal),
)

/**
 * Archivo squeezed to `wdth 78`, the way the landing draws its digits: tall and
 * narrow, which no monospace face can do.
 */
@OptIn(ExperimentalTextApi::class)
val Numerals = FontFamily(
    Font(
        R.font.archivo_variable,
        FontWeight.Light,
        variationSettings = FontVariation.Settings(
            FontVariation.width(78f),
            FontVariation.weight(300),
        ),
    ),
)

/** Uppercase with wide tracking — every label on the landing is set this way. */
val Label = TextStyle(
    fontFamily = Mono,
    fontWeight = FontWeight.Light,
    fontSize = 12.sp,
    letterSpacing = 0.28.em,
)

val Title = TextStyle(
    fontFamily = Mono,
    fontWeight = FontWeight.Light,
    fontSize = 17.sp,
    letterSpacing = 0.4.em,
)

val Body = TextStyle(
    fontFamily = Mono,
    fontWeight = FontWeight.Light,
    fontSize = 14.sp,
    letterSpacing = 0.06.em,
)

val Digits = TextStyle(
    fontFamily = Numerals,
    fontWeight = FontWeight.Light,
    fontFeatureSettings = "tnum",
    letterSpacing = 0.02.em,
)
