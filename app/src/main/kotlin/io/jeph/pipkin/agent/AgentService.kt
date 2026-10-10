package io.jeph.pipkin.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import io.jeph.pipkin.MainActivity
import io.jeph.pipkin.R
import io.jeph.pipkin.appGraph

/**
 * User-started on-device coding agent sessions running in Termux; keeps Pipkin's connection to Pi
 * alive and offers Stop. Foreground service plus partial wake lock, held only while active.
 */
class AgentService : Service() {
  private val handler = Handler(Looper.getMainLooper())
  private lateinit var wakeLock: PowerManager.WakeLock
  private val renew = object : Runnable {
    override fun run() {
      wakeLock.acquire(WAKE_LEASE_MILLIS)
      handler.postDelayed(this, WAKE_RENEW_MILLIS)
    }
  }

  override fun onCreate() {
    super.onCreate()
    wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_TAG).apply {
      setReferenceCounted(false)
    }
    getSystemService(NotificationManager::class.java).createNotificationChannel(
      NotificationChannel(CHANNEL, getString(R.string.agent_notification_channel), NotificationManager.IMPORTANCE_LOW),
    )
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_BUSY -> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
          startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
          startForeground(NOTIFICATION_ID, notification())
        }
        handler.removeCallbacks(renew)
        renew.run()
      }

      // Chats settle after the abort; KeepAlive then releases the service.
      ACTION_STOP -> appGraph.registry.abortAll()

      else -> release()
    }
    return START_NOT_STICKY
  }

  private fun release() {
    handler.removeCallbacks(renew)
    if (wakeLock.isHeld) wakeLock.release()
    stopForeground(STOP_FOREGROUND_REMOVE)
    stopSelf()
  }

  override fun onDestroy() {
    handler.removeCallbacks(renew)
    if (wakeLock.isHeld) wakeLock.release()
    super.onDestroy()
  }

  override fun onBind(intent: Intent?): IBinder? = null

  private fun notification(): Notification {
    val open = PendingIntent.getActivity(this, 20, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
    val stop = PendingIntent.getService(this, 21, intent(this, ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
    return Notification.Builder(this, CHANNEL)
      .setSmallIcon(R.drawable.ic_launcher_foreground)
      .setContentTitle(getString(R.string.agent_notification_title))
      .setContentText(getString(R.string.agent_notification_text))
      .setContentIntent(open)
      .setOngoing(true)
      .addAction(Notification.Action.Builder(null, getString(R.string.agent_notification_stop), stop).build())
      .build()
  }

  companion object {
    const val ACTION_BUSY = "io.jeph.pipkin.action.BUSY"
    const val ACTION_IDLE = "io.jeph.pipkin.action.IDLE"
    const val ACTION_STOP = "io.jeph.pipkin.action.STOP"
    private const val TAG = "Pipkin"
    private const val CHANNEL = "agent"
    private const val NOTIFICATION_ID = 1
    private const val WAKE_TAG = "Pipkin:Agent"
    private const val WAKE_LEASE_MILLIS = 20 * 60_000L
    private const val WAKE_RENEW_MILLIS = 10 * 60_000L

    private fun intent(context: Context, action: String) = Intent(context, AgentService::class.java).setAction(action)

    /**
     * Active → foreground with wake lock; inactive → release and stop. Safe from any thread.
     * Returns false when Android refused the start (`ForegroundServiceStartNotAllowedException`
     * on API 31+, or a background start limit); work continues without the service, but Pipkin
     * may be killed in the background.
     */
    fun update(context: Context, active: Boolean): Boolean = try {
      if (active) {
        context.startForegroundService(intent(context, ACTION_BUSY))
      } else {
        context.startService(intent(context, ACTION_IDLE))
      }
      true
    } catch (error: IllegalStateException) {
      Log.w(TAG, "AgentService.update($active) refused", error)
      false
    }
  }
}
