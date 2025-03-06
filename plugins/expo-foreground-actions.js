const { withAndroidManifest, AndroidConfig } = require("expo/config-plugins");
const { getMainApplicationOrThrow } = AndroidConfig.Manifest;

module.exports = function withBackgroundActions(config) {
  return withAndroidManifest(config, async (config) => {
    const androidManifest = config.modResults;
    const application = getMainApplicationOrThrow(androidManifest);
    const service = application.service ? application.service : [];
    
    // Add required permissions for HEALTH foreground service type
    if (!androidManifest.manifest['uses-permission']) {
      androidManifest.manifest['uses-permission'] = [];
    }
    
    // Add all required permissions if they don't exist
    const requiredPermissions = [
      'android.permission.FOREGROUND_SERVICE',
      'android.permission.FOREGROUND_SERVICE_HEALTH',
      'android.permission.POST_NOTIFICATIONS',
      'android.permission.WAKE_LOCK',
      'android.permission.ACTIVITY_RECOGNITION',
      'android.permission.BODY_SENSORS',
      'android.permission.HIGH_SAMPLING_RATE_SENSORS'
    ];
    
    requiredPermissions.forEach(permission => {
      const exists = androidManifest.manifest['uses-permission'].some(
        p => p.$['android:name'] === permission
      );
      
      if (!exists) {
        androidManifest.manifest['uses-permission'].push({
          $: { 'android:name': permission },
        });
      }
    });

    // Add service with health foreground service type
    config.modResults = {
      manifest: {
        ...androidManifest.manifest,
        application: [
          {
            ...application,
            service: [
              ...service,
              {
                $: {
                  "android:name":
                    "expo.modules.foregroundactions.ExpoForegroundActionsService",
                  "android:exported": "false",
                  "android:foregroundServiceType": "health",
                },
              },
            ],
          },
        ],
      },
    };

    return config;
  });
};
