package com.ozguryilmaz.navinavi

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Navigasyon sürerken çalışan ön plan servisi. GPS'i dinler ve NavSession'a iletir;
 * telefon ekranı kapalıyken ya da sadece Android Auto açıkken de navigasyon sürer.
 */
class NavigationService : Service() {

    private var listening = false

    // Android 10 ve öncesinde bu metotların hepsi zorunlu; lambda yazılırsa çöker.
    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) = NavSession.onLocation(location)
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Android 10 ve öncesi için")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        NavSession.init(this)
        if (intent?.action == ACTION_STOP) {
            NavSession.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } catch (e: Exception) {
            // Ör. konum izni yoksa Android 14+ ön plan servisine izin vermez.
            stopSelf()
            return START_NOT_STICKY
        }
        // Servis bir şekilde yeniden başlatıldıysa ama navigasyon yoksa boşuna GPS açma.
        if (!NavSession.state.value.navigating) {
            stopSelf()
            return START_NOT_STICKY
        }
        startLocation()
        return START_NOT_STICKY
    }

    private fun startLocation() {
        if (listening) return
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val provider = if (fine) LocationManager.GPS_PROVIDER else LocationManager.NETWORK_PROVIDER
        try {
            getSystemService(LocationManager::class.java)
                .requestLocationUpdates(provider, 1000L, 0f, listener, Looper.getMainLooper())
            listening = true
        } catch (e: Exception) {
            // İzin yok ya da konum kapalı; NavSession "GPS bekleniyor" gösterir.
        }
    }

    override fun onDestroy() {
        if (listening) getSystemService(LocationManager::class.java).removeUpdates(listener)
        listening = false
        super.onDestroy()
    }

    private fun notification(): android.app.Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.nav_channel), NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, NavigationService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val dest = NavSession.state.value.destination?.name.orEmpty()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_turn_straight)
            .setContentTitle(getString(R.string.nav_notification_title))
            .setContentText(dest)
            .setContentIntent(open)
            .addAction(0, getString(R.string.stop_nav), stop)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "navigation"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.ozguryilmaz.navinavi.STOP_NAV"
    }
}
