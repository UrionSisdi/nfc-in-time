package io.github.urionsisdi.nfcintime.auth

import android.app.Activity

/**
 * Sign-in produces one thing: a Telegram `id_token` for the server to check
 * against Telegram's JWKS. Nothing here is trusted by anyone — the app never
 * decides who the player is, it only carries the proof.
 */
interface Login {
    suspend fun idToken(activity: Activity): String
}

/**
 * The local path: the server run with `NFCIT_AUTH_MODE=dev` takes `tg_id[:name]`
 * as an id_token and asks for nothing else. It exists so the app can be worked on
 * against `make dev`, and is compiled into debug builds only.
 */
class DevLogin(private val tgId: String, private val name: String) : Login {
    override suspend fun idToken(activity: Activity): String = "$tgId:$name"
}
