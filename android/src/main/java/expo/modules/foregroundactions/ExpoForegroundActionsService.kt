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
import androidx.core.app.NotificationCompat
import com.facebook.react.HeadlessJsTaskService
import com.facebook.react.bridge.Arguments
import com.facebook.react.jstasks.HeadlessJsTaskConfig


class ExpoForegroundActionsService : HeadlessJsTaskService() {
    companion object {
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
            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                    .setContentTitle(notificationTitle)
                    .setContentText(notificationDesc)
                    .setSmallIcon(notificationIconInt)
                    .setContentIntent(contentIntent)
                    .setOngoing(true)
                    .setSilent(true)
                    .setProgress(notificationMaxProgress, notificationProgress, notificationIndeterminate)
                    .setPriority(NotificationCompat.PRIORITY_MIN)
                    .setColor(notificationColor)
            return builder.build()
        }
    }


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val extras: Bundle? = intent?.extras
            if (extras == null) {
                println("ERROR: Extras are null, cannot start foreground service")
                stopSelf()
                return START_NOT_STICKY
            }

            // Safely extract all required values with error handling
            val notificationTitle = extras.getString("notificationTitle")
            if (notificationTitle == null) {
                println("ERROR: notificationTitle is null")
                stopSelf()
                return START_NOT_STICKY
            }

            val notificationDesc = extras.getString("notificationDesc")
            if (notificationDesc == null) {
                println("ERROR: notificationDesc is null")
                stopSelf()
                return START_NOT_STICKY
            }

            val notificationColorStr = extras.getString("notificationColor")
            if (notificationColorStr == null) {
                println("ERROR: notificationColor is null")
                stopSelf()
                return START_NOT_STICKY
            }

            val notificationColor: Int
            try {
                notificationColor = Color.parseColor(notificationColorStr)
            } catch (e: Exception) {
                println("ERROR: Failed to parse color: ${e.message}")
                stopSelf()
                return START_NOT_STICKY
            }

            val notificationIconInt = extras.getInt("notificationIconInt", 0)
            if (notificationIconInt == 0) {
                println("WARNING: notificationIconInt is 0, this might cause issues")
            }

            val notificationProgress = extras.getInt("notificationProgress", 0)
            val notificationMaxProgress = extras.getInt("notificationMaxProgress", 100)
            val notificationIndeterminate = extras.getBoolean("notificationIndeterminate", false)
            val notificationId = extras.getInt("notificationId", 1)
            
            val linkingURI = extras.getString("linkingURI") ?: ""

            println("Service starting with notificationId: $notificationId")
            println("notificationIconInt: $notificationIconInt")
            
            // Create notification channel first
            createNotificationChannel()
            println("Notification channel created")

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
                println("ERROR: Failed to build notification: ${e.message}")
                e.printStackTrace()
                stopSelf()
                return START_NOT_STICKY
            }
            
            println("Starting foreground service with ID: $notificationId")
            
            try {
                startForeground(notificationId, notification)
                println("Foreground service started successfully")
            } catch (e: Exception) {
                println("ERROR: Failed to start foreground service: ${e.message}")
                e.printStackTrace()
                stopSelf()
                return START_NOT_STICKY
            }
            
            return START_STICKY
        } catch (e: Exception) {
            println("FATAL ERROR in onStartCommand: ${e.message}")
            e.printStackTrace()
            stopSelf()
            return START_NOT_STICKY
        }
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    private fun createNotificationChannel() {
        println("Creating notification channel")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val serviceChannel = NotificationChannel(
                    CHANNEL_ID, 
                    "Foreground Service Channel",
                    NotificationManager.IMPORTANCE_LOW
                )
                
                // Configure the notification channel
                serviceChannel.description = "Channel for foreground service notifications"
                serviceChannel.enableLights(false)
                serviceChannel.enableVibration(false)
                serviceChannel.setShowBadge(false)
                
                val manager = getSystemService(NotificationManager::class.java)
                if (manager == null) {
                    println("ERROR: Could not get NotificationManager service")
                    return
                }
                
                manager.createNotificationChannel(serviceChannel)
                println("Notification channel created successfully")
            } catch (e: Exception) {
                println("ERROR: Failed to create notification channel: ${e.message}")
                e.printStackTrace()
            }
        } else {
            println("No need to create notification channel for Android < O")
        }
    }

    override fun getTaskConfig(intent: Intent): HeadlessJsTaskConfig? {
        try {
            val extras = intent.extras
            if (extras == null) {
                println("ERROR: No extras found in intent for headless task")
                return null
            }

            val headlessTaskName = extras.getString("headlessTaskName")
            if (headlessTaskName == null || headlessTaskName.isEmpty()) {
                println("ERROR: headlessTaskName is null or empty")
                return null
            }

            println("Creating HeadlessJsTaskConfig with task name: $headlessTaskName")
            return HeadlessJsTaskConfig(
                headlessTaskName,
                Arguments.fromBundle(extras),
                0, // timeout for the task (0 = no timeout)
                true // allows task to run in foreground
            )
        } catch (e: Exception) {
            println("ERROR: Failed to create HeadlessJsTaskConfig: ${e.message}")
            e.printStackTrace()
            return null
        }
    }
}
