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
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await

object PickupAlerts {
    private const val CHANNEL = "pickup"

    private fun prefs(context: Context) =
        context.getSharedPreferences("pickup-alerts", Context.MODE_PRIVATE)

    fun enabled(context: Context) = prefs(context).getBoolean("enabled", false)

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
        prefs(context).edit().putBoolean("enabled", enabled).apply()
        if (enabled) {
            try {
                register(context)
            } catch (e: Exception) {
                prefs(context).edit().putBoolean("enabled", false).apply()
                throw e
            }
        } else {
            clear(context)
            // Local opt-out takes effect immediately, even if the token cannot sync offline.
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
                FirebaseAuth.getInstance().currentUser?.uid != ownerId
        )
            return
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
