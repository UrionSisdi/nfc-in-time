package io.github.urionsisdi.nfcintime.nfc

import org.json.JSONObject

/**
 * The proprietary AID the two installations find each other by: the `F0` prefix
 * marks it as nobody's registered application, `nfcit` in ASCII, then a version
 * byte so a later protocol can share the air with this one.
 */
val AID = byteArrayOf(0xF0.toByte(), 0x6E, 0x66, 0x63, 0x69, 0x74, 0x01)

const val CLA = 0x80.toByte()

/** Identity of the sender, answered with the identity of the receiver. */
const val INS_HELLO: Byte = 0x10

/** Public key of the sender, answered with the public key of the receiver. */
const val INS_KEY: Byte = 0x11

/** One round of the game, at the frequency of the transport. */
const val INS_TICK: Byte = 0x20

/** The reader's proposal; answered with the card's signature, or refused. */
const val INS_SETTLE: Byte = 0x30

/** The reader's own signature, closing the record on the card side. */
const val INS_CONFIRM: Byte = 0x31

val SW_OK = byteArrayOf(0x90.toByte(), 0x00)
val SW_REFUSED = byteArrayOf(0x69.toByte(), 0x82.toByte())
val SW_UNKNOWN = byteArrayOf(0x6D.toByte(), 0x00)

/** Who the other side is. Small enough to travel in a single APDU. */
data class Hello(val tgId: String, val name: String, val balance: Long) {
    fun encode(): ByteArray = JSONObject()
        .put("id", tgId)
        .put("n", name)
        .put("b", balance)
        .toString()
        .toByteArray()

    companion object {
        fun decode(bytes: ByteArray): Hello {
            val json = JSONObject(bytes.toString(Charsets.UTF_8))
            return Hello(json.getString("id"), json.getString("n"), json.getLong("b"))
        }
    }
}

/**
 * One round: the gravity of the sender and the length of the round as the reader
 * measured it. Both sides integrate over the same pair of numbers, which is why
 * the interval travels on the wire instead of being read off two clocks.
 */
data class Tick(val gravityZ: Double, val millis: Int, val balance: Long) {
    fun encode(): ByteArray = JSONObject()
        .put("g", gravityZ)
        .put("t", millis)
        .put("b", balance)
        .toString()
        .toByteArray()

    companion object {
        fun decode(bytes: ByteArray): Tick {
            val json = JSONObject(bytes.toString(Charsets.UTF_8))
            return Tick(json.getDouble("g"), json.getInt("t"), json.getLong("b"))
        }
    }
}

/** Builds `CLA INS 00 00 Lc <data> 00`. */
fun command(ins: Byte, data: ByteArray): ByteArray {
    require(data.size <= 255) { "frame of ${data.size} bytes does not fit one APDU" }
    return byteArrayOf(CLA, ins, 0x00, 0x00, data.size.toByte()) + data + byteArrayOf(0x00)
}

/** The payload of a command APDU, or null if it is not one of ours. */
fun payload(apdu: ByteArray): ByteArray? {
    if (apdu.size < 5 || apdu[0] != CLA) return null
    val length = apdu[4].toInt() and 0xFF
    if (apdu.size < 5 + length) return null
    return apdu.copyOfRange(5, 5 + length)
}

fun instruction(apdu: ByteArray): Byte? = if (apdu.size >= 2 && apdu[0] == CLA) apdu[1] else null

/** Strips the status word from a response, or null if the card refused. */
fun body(response: ByteArray): ByteArray? {
    if (response.size < 2) return null
    val sw1 = response[response.size - 2]
    val sw2 = response[response.size - 1]
    if (sw1 != SW_OK[0] || sw2 != SW_OK[1]) return null
    return response.copyOfRange(0, response.size - 2)
}

fun ok(data: ByteArray): ByteArray = data + SW_OK

fun isSelect(apdu: ByteArray): Boolean =
    apdu.size >= 4 && apdu[0] == 0x00.toByte() && apdu[1] == 0xA4.toByte()
