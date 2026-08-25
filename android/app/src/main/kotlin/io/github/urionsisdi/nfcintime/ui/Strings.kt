package io.github.urionsisdi.nfcintime.ui

import androidx.compose.runtime.staticCompositionLocalOf
import io.github.urionsisdi.nfcintime.Trouble
import io.github.urionsisdi.nfcintime.time.Units
import java.util.Locale

enum class Lang(val code: String) {
    EN("en"),
    RU("ru"),
    ;

    companion object {
        /** The system's choice, which is the default until the player overrides it. */
        fun ofSystem(): Lang = if (Locale.getDefault().language == RU.code) RU else EN

        fun of(code: String): Lang = entries.firstOrNull { it.code == code } ?: ofSystem()
    }
}

/**
 * Every word in the interface, in one table — the same way the landing carries
 * its two languages. Nothing here is long enough to earn a resource file per
 * locale, and the switch has to work without restarting the game.
 */
data class Strings(
    val signIn: String,
    val signingIn: String,
    val once: String,
    val timeLeftOn: String,
    val years: String,
    val days: String,
    val hours: String,
    val minutes: String,
    val seconds: String,
    val touch: String,
    val turnOnNfc: String,
    val offline: String,
    val noTelegram: String,
    val telegramUnreachable: String,
    val refused: String,
    val noConnection: String,
    val signedOut: String,
    val takingFrom: String,
    val losingTo: String,
    val levelWith: String,
    val zeroed: String,
    val lived: String,
    val settings: String,
    val done: String,
    val name: String,
    val language: String,
    val update: String,
    val checking: String,
    val upToDate: String,
    val install: String,
    val allowInstall: String,
    val updateFailed: String,
    val yearShort: String,
    val dayShort: String,
    val hourShort: String,
    val minuteShort: String,
    val secondShort: String,
) {
    val units = Units(yearShort, dayShort, hourShort, minuteShort, secondShort)
}

private val English = Strings(
    signIn = "sign in with telegram",
    signingIn = "signing in",
    once = "25 years, once",
    timeLeftOn = "time left on",
    years = "years",
    days = "days",
    hours = "hours",
    minutes = "min",
    seconds = "sec",
    touch = "touch another phone",
    turnOnNfc = "turn on nfc",
    offline = "offline, counting locally",
    noTelegram = "telegram is not installed",
    telegramUnreachable = "telegram is not answering",
    refused = "the server turned the sign-in down",
    noConnection = "no connection to the server",
    signedOut = "signed in on another device",
    takingFrom = "taking from",
    losingTo = "losing to",
    levelWith = "level with",
    zeroed = "zeroed",
    lived = "lived",
    settings = "settings",
    done = "done",
    name = "name",
    language = "language",
    update = "version",
    checking = "checking",
    upToDate = "up to date",
    install = "install",
    allowInstall = "allow installs",
    updateFailed = "did not install",
    yearShort = "y",
    dayShort = "d",
    hourShort = "h",
    minuteShort = "m",
    secondShort = "s",
)

private val Russian = Strings(
    signIn = "войти через telegram",
    signingIn = "вход",
    once = "25 лет, один раз",
    timeLeftOn = "времени осталось у",
    years = "лет",
    days = "дней",
    hours = "часов",
    minutes = "мин",
    seconds = "сек",
    touch = "приложи другой телефон",
    turnOnNfc = "включи nfc",
    offline = "нет связи, считаем на месте",
    noTelegram = "телеграм не установлен",
    telegramUnreachable = "телеграм не отвечает",
    refused = "сервер отклонил вход",
    noConnection = "сервер недоступен",
    signedOut = "вход был с другого устройства",
    takingFrom = "забираешь у",
    losingTo = "отдаёшь",
    levelWith = "поровну с",
    zeroed = "обнулён",
    lived = "прожито",
    settings = "настройки",
    done = "готово",
    name = "имя",
    language = "язык",
    update = "версия",
    checking = "проверяю",
    upToDate = "последняя",
    install = "установить",
    allowInstall = "разреши установку",
    updateFailed = "не установилось",
    yearShort = "г",
    dayShort = "д",
    hourShort = "ч",
    minuteShort = "м",
    secondShort = "с",
)

/** The one line the sign-in screen shows when nothing worked. */
fun Strings.say(trouble: Trouble): String = when (trouble) {
    Trouble.NO_TELEGRAM -> noTelegram
    Trouble.TELEGRAM_UNREACHABLE -> telegramUnreachable
    Trouble.REFUSED -> refused
    Trouble.OFFLINE -> noConnection
    Trouble.SIGNED_OUT -> signedOut
}

fun strings(lang: Lang): Strings = when (lang) {
    Lang.EN -> English
    Lang.RU -> Russian
}

val LocalStrings = staticCompositionLocalOf { English }
