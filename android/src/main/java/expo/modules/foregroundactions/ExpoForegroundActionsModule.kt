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
                    val hasPermission = hasNotificationPermission()
                    Log.d(TAG, "Android 13+ detected. Notification permission status: $hasPermission")
                    if (!hasPermission) {
                        Log.d(TAG, "Notification permission not granted, requesting...")
                        requestNotificationPermission()
                    } else {
                        Log.d(TAG, "Notification permission already granted")
                    }
                } else {
                    Log.d(TAG, "Running on Android ${Build.VERSION.SDK_INT}, notification permission automatically granted")
                }
                
                Log.d(TAG, "Creating intent for ExpoForegroundActionsService")
                Log.d(TAG, "Notification details - Title: ${options.notificationTitle}, Desc: ${options.notificationDesc}")
                Log.d(TAG, "Notification color: ${options.notificationColor}")
                
                val intent = Intent(context, ExpoForegroundActionsService::class.java)
                intent.putExtra("headlessTaskName", options.headlessTaskName)
                intent.putExtra("notificationTitle", options.notificationTitle)
                intent.putExtra("notificationDesc", options.notificationDesc)
                intent.putExtra("notificationColor", options.notificationColor)
                
                // Get notification icon resource ID
                val notificationIconInt: Int = context.resources.getIdentifier(options.notificationIconName, options.notificationIconType, context.packageName)
                Log.d(TAG, "Notification icon details - Name: ${options.notificationIconName}, Type: ${options.notificationIconType}")
                Log.d(TAG, "Resolved notification icon resource ID: $notificationIconInt")
                if (notificationIconInt <= 0) {
                    Log.w(TAG, "WARNING: Could not resolve notification icon resource. This may cause notification display issues.")
                }
                intent.putExtra("notificationIconInt", notificationIconInt)
                intent.putExtra("notificationProgress", options.notificationProgress)
                intent.putExtra("notificationMaxProgress", options.notificationMaxProgress)
                intent.putExtra("notificationIndeterminate", options.notificationIndeterminate)
                intent.putExtra("linkingURI", options.linkingURI)
                Log.d(TAG, "Notification progress: ${options.notificationProgress}/${options.notificationMaxProgress}, Indeterminate: ${options.notificationIndeterminate}")
                Log.d(TAG, "Linking URI: ${options.linkingURI}")
                
                currentReferenceId++
                Log.d(TAG, "Generated new notification ID: $currentReferenceId")

                intentMap[currentReferenceId] = intent
                intent.putExtra("notificationId", currentReferenceId)
                
                // For Android 14 (API 34) and above, we need to specify the foreground service type
                try {
                    if (Build.VERSION.SDK_INT >= 34) { // Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                        val serviceType = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
                        intent.putExtra("foregroundServiceType", serviceType)
                        Log.d(TAG, "Starting foreground service with explicit type HEALTH ($serviceType) for Android 14+")
                        Log.d(TAG, "Checking manifest for required permissions...")
                        
                        // Check if the app has the required foreground service type permission
                        val hasHealthPermission = context.packageManager.checkPermission(
                            android.Manifest.permission.FOREGROUND_SERVICE_HEALTH,
                            context.packageName
                        ) == PackageManager.PERMISSION_GRANTED
                        
                        Log.d(TAG, "FOREGROUND_SERVICE_HEALTH permission status: $hasHealthPermission")
                        
                        context.startForegroundService(intent)
                        Log.d(TAG, "Successfully called startForegroundService for Android 14+")
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        Log.d(TAG, "Starting foreground service for Android 8-13")
                        context.startForegroundService(intent)
                        Log.d(TAG, "Successfully called startForegroundService for Android 8-13")
                    } else {
                        Log.d(TAG, "Starting regular service for Android < 8")
                        context.startService(intent)
                        Log.d(TAG, "Successfully called startService for Android < 8")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start service: ${e.javaClass.simpleName} - ${e.message}")
                    e.printStackTrace()
                    throw e
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
                Log.d(TAG, "Updating notification for ID: $identifier")
                Log.d(TAG, "Update details - Title: ${options.notificationTitle}, Desc: ${options.notificationDesc}")
                
                val notificationIconInt: Int = context.resources.getIdentifier(options.notificationIconName, options.notificationIconType, context.packageName)
                Log.d(TAG, "Update icon details - Name: ${options.notificationIconName}, Type: ${options.notificationIconType}")
                Log.d(TAG, "Resolved update icon resource ID: $notificationIconInt")
                
                try {
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
                    )
                    Log.d(TAG, "Notification built successfully")
                    
                    val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    notificationManager.notify(identifier, notification)
                    Log.d(TAG, "Notification updated successfully for ID: $identifier")
                } catch (e: Exception) {
                    Log.e(TAG, "Error building or updating notification: ${e.javaClass.simpleName} - ${e.message}")
                    e.printStackTrace()
                    throw e
                }
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
        
        // Add a debug log function that can be called from JavaScript
        Function("debugLog") { message: String ->
            Log.d(TAG, "JS: $message")
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
            val permissionStatus = ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            )
            val isGranted = permissionStatus == PackageManager.PERMISSION_GRANTED
            Log.d(TAG, "POST_NOTIFICATIONS permission check - Status code: $permissionStatus, Granted: $isGranted")
            return isGranted
        }
        Log.d(TAG, "POST_NOTIFICATIONS permission check not needed for Android < 13")
        return true
    }
    
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                // In Expo modules, we need to get the current activity from the appContext
                val activity = appContext.activityProvider?.currentActivity
                Log.d(TAG, "Attempting to request POST_NOTIFICATIONS permission")
                
                if (activity != null) {
                    Log.d(TAG, "Current activity found: ${activity.javaClass.simpleName}")
                    
                    // Check if we should show rationale
                    val shouldShowRationale = ActivityCompat.shouldShowRequestPermissionRationale(
                        activity, 
                        android.Manifest.permission.POST_NOTIFICATIONS
                    )
                    Log.d(TAG, "Should show permission rationale: $shouldShowRationale")
                    
                    ActivityCompat.requestPermissions(
                        activity,
                        arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                        PERMISSION_REQUEST_CODE
                    )
                    Log.d(TAG, "Permission request initiated with code: $PERMISSION_REQUEST_CODE")
                } else {
                    Log.e(TAG, "Cannot request notification permission: activity is null")
                    Log.d(TAG, "Current ReactContext state: ${context.javaClass.simpleName}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error requesting notification permission: ${e.javaClass.simpleName} - ${e.message}")
                e.printStackTrace()
            }
        } else {
            Log.d(TAG, "No need to request POST_NOTIFICATIONS permission for Android < 13")
        }
    }
}
