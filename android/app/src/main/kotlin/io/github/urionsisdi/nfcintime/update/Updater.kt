package io.github.urionsisdi.nfcintime.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.Settings
import android.util.Log
import io.github.urionsisdi.nfcintime.BuildConfig
import io.github.urionsisdi.nfcintime.net.Api
import io.github.urionsisdi.nfcintime.net.Release
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "nfcit.update"

/** Where the update line in settings currently stands. */
sealed interface UpdateState {
    /** Nothing asked yet; the line shows the version that is installed. */
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object Current : UpdateState
    data class Ready(val versionName: String) : UpdateState
    data class Downloading(val percent: Int) : UpdateState

    /** Installing from anywhere but a store is a per-app grant the player gives. */
    data object NeedsPermission : UpdateState
    data object Failed : UpdateState
}

/**
 * The whole update mechanism: ask the server which build is current, fetch the
 * APK from the release, hand it to the system installer. There is no store to do
 * this for us, and nothing here happens without the player tapping first.
 */
class Updater(
    private val context: Context,
    private val api: Api,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private var release: Release? = null

    /**
     * One tap, whatever the line says: check, install, or ask again after a
     * failure. A check already running is left to finish.
     */
    fun act() {
        when (_state.value) {
            is UpdateState.Checking, is UpdateState.Downloading -> return
            is UpdateState.Ready, is UpdateState.NeedsPermission -> release?.let(::install) ?: check()
            else -> check()
        }
    }

    private fun check() {
        _state.value = UpdateState.Checking
        scope.launch(Dispatchers.IO) {
            _state.value = try {
                val current = api.release().also { release = it }
                if (current.versionCode > BuildConfig.VERSION_CODE) {
                    UpdateState.Ready(current.versionName)
                } else {
                    UpdateState.Current
                }
            } catch (e: IOException) {
                Log.w(TAG, "version check failed", e)
                UpdateState.Failed
            }
        }
    }

    private fun install(release: Release) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            _state.value = UpdateState.NeedsPermission
            askForPermission()
            return
        }
        _state.value = UpdateState.Downloading(0)
        scope.launch(Dispatchers.IO) {
            try {
                stream(release.url)
                // The installer takes over from here: the system asks, and the
                // process is replaced if the player agrees.
            } catch (e: IOException) {
                Log.w(TAG, "download failed", e)
                _state.value = UpdateState.Failed
            }
        }
    }

    /**
     * The APK goes from the socket straight into the install session — writing it
     * to storage first would only mean a copy to clean up afterwards.
     */
    private fun stream(url: String) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MILLIS
            readTimeout = TIMEOUT_MILLIS
        }
        try {
            val total = connection.contentLengthLong
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            if (total > 0) params.setSize(total)

            val id = installer.createSession(params)
            installer.openSession(id).use { session ->
                session.openWrite(APK_ENTRY, 0, total).use { sink ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    var written = 0L
                    connection.inputStream.use { source ->
                        while (true) {
                            val read = source.read(buffer)
                            if (read < 0) break
                            sink.write(buffer, 0, read)
                            written += read
                            if (total > 0) {
                                _state.value = UpdateState.Downloading((written * 100 / total).toInt())
                            }
                        }
                    }
                    session.fsync(sink)
                }
                session.commit(callback(id).intentSender)
            }
        } finally {
            connection.disconnect()
        }
    }

    /** Mutable, because the system fills the result into it. */
    private fun callback(sessionId: Int): PendingIntent = PendingIntent.getBroadcast(
        context,
        sessionId,
        Intent(context, InstallReceiver::class.java).setPackage(context.packageName),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    private fun askForPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { Log.w(TAG, "no unknown-sources screen on this device", it) }
    }

    /** Reported by [InstallReceiver] once the system is done with the session. */
    fun onInstallFailed() {
        _state.value = UpdateState.Failed
    }

    private companion object {
        const val APK_ENTRY = "nfcintime"
        const val BUFFER_BYTES = 64 * 1024
        const val TIMEOUT_MILLIS = 30_000
    }
}
