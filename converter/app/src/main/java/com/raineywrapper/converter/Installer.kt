package com.raineywrapper.converter

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

/** Installs an APK through PackageInstaller; Android shows its own confirmation screen. */
object Installer {
    private const val ACTION = "com.raineywrapper.converter.INSTALL_RESULT"

    sealed class Result {
        object Success : Result()
        class Failure(val message: String) : Result()
    }

    suspend fun install(context: Context, apk: File, packageName: String, onProgress: (Float) -> Unit): Result {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= 31) {
                // Lets updates of games this app installed go through without a prompt.
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { input ->
                    val buffer = ByteArray(1 shl 20)
                    var written = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        written += n
                        onProgress(written.toFloat() / apk.length())
                    }
                }
                session.fsync(out)
            }

            return suspendCancellableCoroutine { cont ->
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context, intent: Intent) {
                        if (intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1) != sessionId) return
                        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                                @Suppress("DEPRECATION")
                                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                ctx.startActivity(confirm)
                            }
                            else -> {
                                ctx.unregisterReceiver(this)
                                val result = if (status == PackageInstaller.STATUS_SUCCESS) {
                                    Result.Success
                                } else {
                                    Result.Failure(describe(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)))
                                }
                                if (cont.isActive) cont.resume(result)
                            }
                        }
                    }
                }
                ContextCompat.registerReceiver(context, receiver, IntentFilter(ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
                cont.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }

                val callback = PendingIntent.getBroadcast(
                    context, sessionId, Intent(ACTION).setPackage(context.packageName),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(callback.intentSender)
            }
        }
    }

    private fun describe(status: Int, message: String?): String = when (status) {
        PackageInstaller.STATUS_FAILURE_ABORTED -> "Install was cancelled."
        PackageInstaller.STATUS_FAILURE_CONFLICT ->
            "An app with this name is already installed but was signed with a different key. " +
                "Uninstall it first (this deletes its saves), or give this game a different title."
        PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough storage to install."
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "This device can't run the generated app."
        PackageInstaller.STATUS_FAILURE_BLOCKED -> "Install was blocked by the system."
        else -> "Install failed" + (message?.let { ": $it" } ?: ".")
    }
}
