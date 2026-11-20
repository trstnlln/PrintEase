package com.printease.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await

object PickupAlerts {
    private const val CHANNEL = "pickup"

    private fun prefs(context: Context) =
        context.getSharedPreferences("pickup-alerts", Context.MODE_PRIVATE)

    fun enabled(context: Context) = prefs(context).getBoolean("enabled", false)

    fun owner(context: Context) = prefs(context).getString("owner", null)

    fun stop(context: Context) {
        prefs(context).edit().putBoolean("enabled", false).remove("owner").apply()
        WorkManager.getInstance(context).cancelUniqueWork("pickup-checks")
        WorkManager.getInstance(context).cancelUniqueWork("pickup-check-now")
        clear(context)
    }

    private fun schedule(context: Context) {
        val constraints =
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(
                "pickup-checks",
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<PickupWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(constraints)
                    .build(),
            )
        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                "pickup-check-now",
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<PickupWorker>().setConstraints(constraints).build(),
            )
    }

    fun allowed(context: Context) =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED

    private fun key(token: String) =
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") {
            "%02x".format(it)
        }

    suspend fun register(context: Context, freshToken: String? = null) {
        if (BuildConfig.DEMO || !enabled(context) || !allowed(context)) return
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        if (owner(context) != uid) {
            stop(context)
            return
        }
        schedule(context)
        val token =
            freshToken ?: withTimeout(15000) { FirebaseMessaging.getInstance().token.await() }
        if (FirebaseAuth.getInstance().currentUser?.uid != uid || !enabled(context)) return
        withTimeout(15000) {
            FirebaseFirestore.getInstance()
                .collection("users")
                .document(uid)
                .collection("devices")
                .document(key(token))
                .set(mapOf("token" to token, "updatedAt" to System.currentTimeMillis()))
                .await()
        }
    }

    suspend fun unregister() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        withTimeout(10000) {
            val token = FirebaseMessaging.getInstance().token.await()
            FirebaseFirestore.getInstance()
                .collection("users")
                .document(uid)
                .collection("devices")
                .document(key(token))
                .delete()
                .await()
        }
    }

    suspend fun setEnabled(context: Context, enabled: Boolean) {
        if (enabled) {
            check(allowed(context)) { "Allow notifications in Android Settings first." }
            val uid = FirebaseAuth.getInstance().currentUser?.uid ?: error("Sign in first.")
            prefs(context).edit().putBoolean("enabled", true).putString("owner", uid).apply()
            schedule(context)
            // Periodic checks work without a deployed Firebase Functions backend.
            // FCM registration is optional; its failure must not disable local reminders.
            try {
                register(context)
            } catch (e: CancellationException) {
                // A local FCM timeout must not turn off periodic reminders. Preserve
                // cancellation when the account screen's own coroutine is cancelled.
                currentCoroutineContext().ensureActive()
            } catch (_: Exception) {}
        } else {
            stop(context)
            try {
                unregister()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {}
        }
    }

    fun clear(context: Context) {
        NotificationManagerCompat.from(context).cancelAll()
    }

    @android.annotation.SuppressLint("MissingPermission")
    fun show(context: Context, ownerId: String, orderId: String) {
        if (
            BuildConfig.DEMO ||
                !enabled(context) ||
                !allowed(context) ||
                FirebaseAuth.getInstance().currentUser?.uid != ownerId ||
                owner(context) != ownerId
        )
            return
        val delivered = "seen:$ownerId:$orderId"
        synchronized(this) {
            if (prefs(context).getBoolean(delivered, false)) return
            prefs(context).edit().putBoolean(delivered, true).apply()
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Pickup updates", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val intent =
            Intent(context, MainActivity::class.java)
                .putExtra("orderId", orderId)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending =
            PendingIntent.getActivity(
                context,
                orderId.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val notification =
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_pickup_notification)
                .setContentTitle("Your prints are ready")
                .setContentText("Open PrintEase for pickup details.")
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .build()
        manager.notify(orderId.hashCode(), notification)
    }
}

class PickupMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        if (message.data["type"] != "ready") return
        val ownerId = message.data["ownerId"] ?: return
        val orderId = message.data["orderId"] ?: return
        PickupAlerts.show(this, ownerId, orderId)
    }

    override fun onNewToken(token: String) {
        // Firebase persists the token; account restoration retries registration if this attempt
        // fails.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { PickupAlerts.register(applicationContext, token) }
        }
    }
}

/** Android may defer this check in battery saver or while the app is force-stopped. */
class PickupWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val context = applicationContext
        if (BuildConfig.DEMO || !PickupAlerts.enabled(context) || !PickupAlerts.allowed(context))
            return Result.success()
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return Result.success()
        if (PickupAlerts.owner(context) != uid) return Result.success()
        return try {
            val snapshot =
                withTimeout(25000) {
                    FirebaseFirestore.getInstance()
                        .collection("orders")
                        .whereEqualTo("ownerId", uid)
                        .orderBy(
                            "createdAt",
                            com.google.firebase.firestore.Query.Direction.DESCENDING,
                        )
                        .limit(100)
                        .get(com.google.firebase.firestore.Source.SERVER)
                        .await()
                }
            snapshot.documents
                .filter { it.getString("status") == OrderStatus.Ready.label }
                .forEach { PickupAlerts.show(context, uid, it.id) }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
