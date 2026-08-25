package io.github.urionsisdi.nfcintime.nfc

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.tech.IsoDep
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Reader mode, held for as long as the app is in front. Both devices offer both
 * halves at once — card emulation is always on, reader mode runs beside it — and
 * whichever one sees the other first takes the reader role for that contact.
 *
 * Reader mode is pulsed rather than held down. A polling phone drives its own
 * field, and two fields at once cancel each other where the antennas overlap:
 * held down on both sides, the pair only couples when pressed antenna to
 * antenna. The gaps let each phone be a plain card for a moment, and the jitter
 * keeps two identical devices from pulsing in step forever.
 */
class ReaderLink(
    private val activity: Activity,
    private val contact: Contact,
    private val scope: CoroutineScope,
) {
    private val adapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(activity)
    private val worker = Executors.newSingleThreadExecutor()
    private val reading = AtomicBoolean(false)
    private var pulse: Job? = null

    val enabled: Boolean get() = adapter?.isEnabled == true

    fun start() {
        if (adapter == null || pulse != null) return
        pulse = scope.launch {
            while (true) {
                // Mid-contact the field belongs to whoever holds it: cutting reader
                // mode under a live session would drop the tag.
                if (reading.get() || contact.state.value != null) {
                    delay(REST_MILLIS)
                    continue
                }
                enable()
                delay(POLL_MILLIS)
                if (reading.get()) continue
                disable()
                delay(REST_MILLIS + Random.nextLong(JITTER_MILLIS))
            }
        }
    }

    fun stop() {
        pulse?.cancel()
        pulse = null
        disable()
    }

    private fun enable() = adapter?.enableReaderMode(
        activity,
        { tag ->
            IsoDep.get(tag)?.let { iso ->
                reading.set(true)
                worker.execute {
                    try {
                        contact.runReader(iso)
                    } finally {
                        reading.set(false)
                    }
                }
            }
        },
        NfcAdapter.FLAG_READER_NFC_A or
            NfcAdapter.FLAG_READER_NFC_B or
            NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
        null,
    )

    private fun disable() = adapter?.disableReaderMode(activity)

    private companion object {
        const val POLL_MILLIS = 300L
        const val REST_MILLIS = 200L
        const val JITTER_MILLIS = 300L
    }
}
