package expo.modules.foregroundactions

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import expo.modules.kotlin.Promise
import expo.modules.kotlin.exception.CodedException
import expo.modules.kotlin.exception.toCodedException
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition


const val ON_EXPIRATION_EVENT = "onExpirationEvent"

class ExpoForegroundActionsModule : Module() {
    companion object {
        private const val TAG = "ExpoForegroundAction"
        private const val PERMISSION_REQUEST_CODE = 1001
    }
    
    private val intentMap: MutableMap<Int, Intent> = mutableMapOf()
    private var currentReferenceId: Int = 0


    // Each module class must implement the definition function. The definition consists of components
    // that describes the module's functionality and behavior.
    // See https://docs.expo.dev/modules/module-api for more details about available components.
    @SuppressLint("DiscouragedApi")
    override fun definition() = ModuleDefinition {
        Events(ON_EXPIRATION_EVENT)

        // Sets the name of the module that JavaScript code will use to refer to the module. Takes a string as an argument.
        // Can be inferred from module's class name, but it's recommended to set it explicitly for clarity.
        // The module will be accessible from `requireNativeModule('ExpoForegroundActions')` in JavaScript.
        Name("ExpoForegroundActions")


        AsyncFunction("startForegroundAction") { options: ExpoForegroundOptions, promise: Promise ->
            try {
                // Check notification permission for Android 13+
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    if (!hasNotificationPermission()) {
                        Log.d(TAG, "Notification permission not granted, requesting...")
                        requestNotificationPermission()
                    }
                }
                
                val intent = Intent(context, ExpoForegroundActionsService::class.java)
                intent.putExtra("headlessTaskName", options.headlessTaskName)
                intent.putExtra("notificationTitle", options.notificationTitle)
                intent.putExtra("notificationDesc", options.notificationDesc)
                intent.putExtra("notificationColor", options.notificationColor)
                val notificationIconInt: Int = context.resources.getIdentifier(options.notificationIconName, options.notificationIconType, context.packageName)
                intent.putExtra("notificationIconInt", notificationIconInt)
                intent.putExtra("notificationProgress", options.notificationProgress)
                intent.putExtra("notificationMaxProgress", options.notificationMaxProgress)
                intent.putExtra("notificationIndeterminate", options.notificationIndeterminate)
                intent.putExtra("linkingURI", options.linkingURI)
                currentReferenceId++

                intentMap[currentReferenceId] = intent
                intent.putExtra("notificationId", currentReferenceId)
                
                // For Android 14 (API 34) and above, we need to specify the foreground service type
                if (Build.VERSION.SDK_INT >= 34) { // Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                    intent.putExtra("foregroundServiceType", android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
                    Log.d(TAG, "Starting foreground service with explicit type HEALTH for Android 14+")
                    context.startForegroundService(intent)
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Log.d(TAG, "Starting foreground service for Android 8-13")
                    context.startForegroundService(intent)
                } else {
                    Log.d(TAG, "Starting regular service for Android < 8")
                    context.startService(intent)
                }
                promise.resolve(currentReferenceId)

            } catch (e: Exception) {
                Log.e(TAG, "Error starting foreground action: ${e.message}")
                e.printStackTrace()

                // Handle other exceptions
                promise.reject(e.toCodedException())
            }
        }

        AsyncFunction("stopForegroundAction") { identifier: Int, promise: Promise ->
            try {
                Log.d(TAG, "Stopping foreground action with ID: $identifier")
                Log.d(TAG, "Current intent map: $intentMap")
                val intent = intentMap[identifier]
                if (intent !== null) {
                    context.stopService(intent)
                    intentMap.remove(identifier)
                } else {
                    Log.w(TAG, "Background task with identifier $identifier does not exist or has already been ended")

                }
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping foreground action: ${e.message}")
                e.printStackTrace()
                // Handle other exceptions
                promise.reject(e.toCodedException())
            }
            promise.resolve(null)
        }

        AsyncFunction("updateForegroundedAction") { identifier: Int, options: ExpoForegroundOptions, promise: Promise ->
            try {
                val notificationIconInt: Int = context.resources.getIdentifier(options.notificationIconName, options.notificationIconType, context.packageName)
                val notification: Notification = ExpoForegroundActionsService.buildNotification(
                        context,
                        options.notificationTitle,
                        options.notificationDesc,
                        Color.parseColor(options.notificationColor),
                        notificationIconInt,
                        options.notificationProgress,
                        options.notificationMaxProgress,
                        options.notificationIndeterminate,
                        options.linkingURI,
                );
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.notify(identifier, notification)
                promise.resolve(null)
            } catch (e: Exception) {
                Log.e(TAG, "Error updating foreground action: ${e.message}")
                e.printStackTrace()
                // Handle other exceptions
                promise.reject(e.toCodedException())
            }
        }

        AsyncFunction("forceStopAllForegroundActions") { promise: Promise ->
            try {
                if (intentMap.isEmpty()) {
                    Log.d(TAG, "No intents to stop.")
                } else {
                    for ((_, intent) in intentMap) {
                        context.stopService(intent)
                    }
                    intentMap.clear()
                }
                promise.resolve(null)
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping all foreground actions: ${e.message}")
                e.printStackTrace()
                // Handle other exceptions
                promise.reject(e.toCodedException())
            }
        }
        AsyncFunction("getForegroundIdentifiers") { promise: Promise ->
            val identifiers = intentMap.keys.toTypedArray()
            promise.resolve(identifiers)
        }
    }

    private val context
        get() = requireNotNull(appContext.reactContext) {
            "React Application Context is null"
        }

    private val applicationContext
        get() = requireNotNull(this.context.applicationContext) {
            "React Application Context is null"
        }
        
    private fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        }
        return true
    }
    
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                val activity = context.currentActivity
                if (activity != null) {
                    ActivityCompat.requestPermissions(
                        activity,
                        arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                        PERMISSION_REQUEST_CODE
                    )
                } else {
                    Log.e(TAG, "Cannot request notification permission: activity is null")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error requesting notification permission: ${e.message}")
                e.printStackTrace()
            }
        }
    }
}
