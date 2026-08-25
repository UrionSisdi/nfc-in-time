package io.github.urionsisdi.nfcintime.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import io.github.urionsisdi.nfcintime.App

/**
 * Where the install session reports back. The interesting case is the first one:
 * the system will not raise its own confirmation dialog, it hands us the intent
 * that opens it.
 */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let(context::startActivity)
            }

            PackageInstaller.STATUS_SUCCESS -> Unit

            else -> {
                Log.w(TAG, "install failed: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
                (context.applicationContext as App).game.updater.onInstallFailed()
            }
        }
    }

    private companion object {
        const val TAG = "nfcit.update"
    }
}
