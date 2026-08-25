package io.github.urionsisdi.nfcintime.nfc

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import io.github.urionsisdi.nfcintime.App

/**
 * The card half of a contact. Android picks which device ends up here; the game
 * itself does not care, both sides run the same arithmetic.
 */
class CardService : HostApduService() {
    private val contact: Contact get() = (application as App).contact

    override fun processCommandApdu(apdu: ByteArray?, extras: Bundle?): ByteArray =
        apdu?.let { contact.onApdu(it) } ?: SW_UNKNOWN

    override fun onDeactivated(reason: Int) = contact.onDeactivated()
}
