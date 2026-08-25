package io.github.urionsisdi.nfcintime

import android.app.Application
import io.github.urionsisdi.nfcintime.nfc.Contact
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

/**
 * The process. `CardService` is started by the NFC stack rather than by us, so
 * the contact has to hang off something that outlives any activity.
 */
class App : Application() {
    private val scope = CoroutineScope(SupervisorJob())

    lateinit var game: Game
        private set

    val contact: Contact get() = game.contact

    override fun onCreate() {
        super.onCreate()
        game = Game(this, scope)
        game.start()
    }
}
