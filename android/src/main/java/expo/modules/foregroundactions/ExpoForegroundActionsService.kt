package expo.modules.foregroundactions

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.facebook.react.HeadlessJsTaskService
import com.facebook.react.bridge.Arguments
import com.facebook.react.jstasks.HeadlessJsTaskConfig


class ExpoForegroundActionsService : HeadlessJsTaskService() {
    companion object {
        private const val TAG = "ExpoForegroundAction"
        private const val CHANNEL_ID = "ExpoForegroundActionChannel"
        fun buildNotification(
                context: Context,
                notificationTitle: String,
                notificationDesc: String,
                notificationColor: Int,
                notificationIconInt: Int,
                notificationProgress: Int,
                notificationMaxProgress: Int,
                notificationIndeterminate: Boolean,
                linkingURI: String
        ): Notification {

            val notificationIntent: Intent = if (linkingURI.isNotEmpty()) {
                Intent(Intent.ACTION_VIEW, Uri.parse(linkingURI))
            } else {
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val contentIntent: PendingIntent = PendingIntent.getActivity(context, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);
            // Log notification icon information
            Log.d(TAG, "Building notification with icon: $notificationIconInt")
            
            // Check if the icon is valid, use app icon as fallback
            var iconToUse = notificationIconInt
            if (iconToUse <= 0) {
                Log.w(TAG, "Invalid notification icon, using application icon as fallback")
                try {
                    val appInfo = context.packageManager.getApplicationInfo(context.packageName, 0)
                    iconToUse = appInfo.icon
                    Log.d(TAG, "Using app icon as fallback: $iconToUse")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to get app icon, using default android icon")
                    iconToUse = android.R.drawable.ic_dialog_info
                }
            }
            
            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                    .setContentTitle(notificationTitle)
                    .setContentText(notificationDesc)
                    .setSmallIcon(iconToUse)
                    .setContentIntent(contentIntent)
                    .setOngoing(true)
                    .setProgress(notificationMaxProgress, notificationProgress, notificationIndeterminate)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setColor(notificationColor)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .setCategory(NotificationCompat.CATEGORY_SERVICE)
            return builder.build()
        }
    }


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val extras: Bundle? = intent?.extras
            if (extras == null) {
                Log.e(TAG, "ERROR: Extras are null, cannot start foreground service")
                stopSelf()
                return START_NOT_STICKY
            }

            // Safely extract all required values with error handling
            val notificationTitle = extras.getString("notificationTitle")
            if (notificationTitle == null) {
                Log.e(TAG, "ERROR: notificationTitle is null")
                stopSelf()
                return START_NOT_STICKY
            }

            val notificationDesc = extras.getString("notificationDesc")
            if (notificationDesc == null) {
                Log.e(TAG, "ERROR: notificationDesc is null")
                stopSelf()
                return START_NOT_STICKY
            }

            val notificationColorStr = extras.getString("notificationColor")
            if (notificationColorStr == null) {
                Log.e(TAG, "ERROR: notificationColor is null")
                stopSelf()
                return START_NOT_STICKY
            }

            val notificationColor: Int
            try {
                notificationColor = Color.parseColor(notificationColorStr)
            } catch (e: Exception) {
                Log.e(TAG, "ERROR: Failed to parse color: ${e.message}")
                stopSelf()
                return START_NOT_STICKY
            }

            val notificationIconInt = extras.getInt("notificationIconInt", 0)
            if (notificationIconInt == 0) {
                Log.w(TAG, "WARNING: notificationIconInt is 0, this might cause issues")
            }

            val notificationProgress = extras.getInt("notificationProgress", 0)
            val notificationMaxProgress = extras.getInt("notificationMaxProgress", 100)
            val notificationIndeterminate = extras.getBoolean("notificationIndeterminate", false)
            val notificationId = extras.getInt("notificationId", 1)
            
            val linkingURI = extras.getString("linkingURI") ?: ""

            Log.d(TAG, "Service starting with notificationId: $notificationId")
            Log.d(TAG, "notificationIconInt: $notificationIconInt")
            
            // Create notification channel first
            createNotificationChannel()
            Log.d(TAG, "Notification channel created")

            // Build the notification
            val notification = try {
                buildNotification(
                    this,
                    notificationTitle,
                    notificationDesc,
                    notificationColor,
                    notificationIconInt,
                    notificationProgress,
                    notificationMaxProgress,
                    notificationIndeterminate,
                    linkingURI
                )
            } catch (e: Exception) {
                Log.e(TAG, "ERROR: Failed to build notification: ${e.message}")
                e.printStackTrace()
                stopSelf()
                return START_NOT_STICKY
            }
            
            Log.d(TAG, "Starting foreground service with ID: $notificationId")
            
            try {
                // For Android 14 (API 34) and above, we need to specify the foreground service type
                if (Build.VERSION.SDK_INT >= 34) { // Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                    val foregroundServiceType = extras.getInt("foregroundServiceType", android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
                    Log.d(TAG, "Starting foreground service with type: $foregroundServiceType for Android 14+")
                    startForeground(notificationId, notification, foregroundServiceType)
                } else {
                    startForeground(notificationId, notification)
                }
                Log.d(TAG, "Foreground service started successfully")
            } catch (e: Exception) {
                Log.e(TAG, "ERROR: Failed to start foreground service: ${e.message}")
                e.printStackTrace()
                stopSelf()
                return START_NOT_STICKY
            }
            
            return START_STICKY
        } catch (e: Exception) {
            Log.e(TAG, "FATAL ERROR in onStartCommand: ${e.message}")
            e.printStackTrace()
            stopSelf()
            return START_NOT_STICKY
        }
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    private fun createNotificationChannel() {
        Log.d(TAG, "Creating notification channel")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val serviceChannel = NotificationChannel(
                    CHANNEL_ID, 
                    "Foreground Service Channel",
                    NotificationManager.IMPORTANCE_DEFAULT
                )
                
                // Configure the notification channel
                serviceChannel.description = "Channel for foreground service notifications"
                serviceChannel.enableLights(true)
                serviceChannel.lightColor = Color.BLUE
                serviceChannel.enableVibration(true)
                serviceChannel.vibrationPattern = longArrayOf(0, 100, 200, 300)
                serviceChannel.setShowBadge(true)
                serviceChannel.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                
                val manager = getSystemService(NotificationManager::class.java)
                if (manager == null) {
                    Log.e(TAG, "ERROR: Could not get NotificationManager service")
                    return
                }
                
                manager.createNotificationChannel(serviceChannel)
                Log.d(TAG, "Notification channel created successfully")
            } catch (e: Exception) {
                Log.e(TAG, "ERROR: Failed to create notification channel: ${e.message}")
                e.printStackTrace()
            }
        } else {
            Log.d(TAG, "No need to create notification channel for Android < O")
        }
    }

    override fun getTaskConfig(intent: Intent): HeadlessJsTaskConfig? {
        try {
            val extras = intent.extras
            if (extras == null) {
                Log.e(TAG, "ERROR: No extras found in intent for headless task")
                return null
            }

            val headlessTaskName = extras.getString("headlessTaskName")
            if (headlessTaskName == null || headlessTaskName.isEmpty()) {
                Log.e(TAG, "ERROR: headlessTaskName is null or empty")
                return null
            }

            Log.d(TAG, "Creating HeadlessJsTaskConfig with task name: $headlessTaskName")
            return HeadlessJsTaskConfig(
                headlessTaskName,
                Arguments.fromBundle(extras),
                0, // timeout for the task (0 = no timeout)
                true // allows task to run in foreground
            )
        } catch (e: Exception) {
            Log.e(TAG, "ERROR: Failed to create HeadlessJsTaskConfig: ${e.message}")
            e.printStackTrace()
            return null
        }
    }
}
