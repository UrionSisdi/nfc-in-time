package io.github.urionsisdi.nfcintime.data

import io.github.urionsisdi.nfcintime.time.Ledger
import org.json.JSONObject

/** What the server says about the player. Its numbers always win over ours. */
data class PlayerState(
    val tgId: String,
    val keyId: String,
    val name: String,
    val balanceSeconds: Long,
    val bornAt: Long,
    val diedAt: Long?,
    /** Nonces of the reported transfers the ledger has taken, applied or duplicate alike. */
    val settled: List<String>,
) {
    companion object {
        fun of(json: JSONObject): PlayerState {
            val transfers = json.optJSONArray("transfers")
            return PlayerState(
                tgId = json.getString("tg_id"),
                keyId = json.getString("key_id"),
                name = json.getString("name"),
                balanceSeconds = json.getLong("balance_seconds"),
                bornAt = json.getLong("born_at"),
                diedAt = if (json.isNull("died_at")) null else json.getLong("died_at"),
                settled = buildList {
                    for (i in 0 until (transfers?.length() ?: 0)) {
                        add(transfers!!.getJSONObject(i).getString("nonce"))
                    }
                },
            )
        }
    }
}

/** Everything the app knows about itself between launches. */
data class Profile(
    val tgId: String = "",
    val keyId: String = "",
    val name: String = "",
    val ledger: Ledger = Ledger(0, 0),
    val prevHash: String = "",
    /** Empty until the player picks one, and then the system's choice stops applying. */
    val language: String = "",
    val bornAt: Long = 0,
    val diedAt: Long? = null,
) {
    val signedIn: Boolean get() = tgId.isNotEmpty() && keyId.isNotEmpty()
}
