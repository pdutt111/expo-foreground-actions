import {
  NativeModulesProxy,
  EventEmitter,
  Subscription,
  Platform
} from "expo-modules-core";
import { platformApiLevel } from "expo-device";
import {
  ExpireEventPayload,
  AndroidSettings,
  ForegroundApi, Settings
} from "./ExpoForegroundActions.types";
import ExpoForegroundActionsModule from "./ExpoForegroundActionsModule";
import { AppRegistry, AppState, NativeModules } from "react-native";

// Add a debug logger function
const DEBUG_TAG = "ExpoForegroundActions";
const debug = (message: string, ...args: any[]) => {
  const logMessage = `[${DEBUG_TAG}] ${message}`;
  console.log(logMessage, ...args);
  
  // For Android, also log to native so it appears in logcat
  if (Platform.OS === 'android' && NativeModules.ExpoForegroundActions) {
    try {
      const fullMessage = args.length > 0 
        ? `${logMessage} ${args.map(arg => JSON.stringify(arg)).join(' ')}` 
        : logMessage;
      NativeModules.ExpoForegroundActions.debugLog(fullMessage);
    } catch (e) {
      console.warn("Failed to log to native:", e);
    }
  }
};

// Set up global error handling for promises
// This is a React Native compatible approach for handling unhandled promise rejections
const originalConsoleError = console.error;
console.error = function(...args) {
  debug('Caught error in console.error:', ...args);
  originalConsoleError.apply(console, args);
};

const emitter = new EventEmitter(
  ExpoForegroundActionsModule ?? NativeModulesProxy.ExpoForegroundActions
);

let ranTaskCount: number = 0;
let jsIdentifier: number = 0;


export class NotForegroundedError extends Error {
  constructor(message: string) {
    super(message); // (1)
    this.name = "NotForegroundedError"; // (2)
  }
}

const startForegroundAction = async (options?: AndroidSettings): Promise<number> => {
  debug("Starting foreground action", options);
  
  if (Platform.OS === "android" && !options) {
    debug("Error: Missing options for Android");
    throw new Error("Foreground action options cannot be null on android");
  }
  
  try {
    let result: number;
    if (Platform.OS === "android") {
      debug("Calling native startForegroundAction for Android");
      result = await ExpoForegroundActionsModule.startForegroundAction(options);
    } else {
      debug("Calling native startForegroundAction for iOS");
      result = await ExpoForegroundActionsModule.startForegroundAction();
    }
    debug(`Foreground action started with ID: ${result}`);
    return result;
  } catch (error) {
    debug(`Failed to start foreground action: ${error.message}`, error);
    throw error;
  }
};


// Get the native constant value.
export const runForegroundedAction = async (act: (api: ForegroundApi) => Promise<void>, androidSettings: AndroidSettings, settings: Settings = { runInJS: false }): Promise<void> => {
  if (!androidSettings) {
    debug("Error: androidSettings is null or undefined");
    throw new Error("Foreground action options cannot be null");
  }
  
  debug(`Starting foreground action with current AppState: ${AppState.currentState}`);
  // We're removing the background check to allow the action to run in background

  if (Platform.OS === "android" && platformApiLevel && platformApiLevel < 26) {
    debug(`Android API level < 26, forcing runInJS to true`);
    settings.runInJS = true;
  }

  const headlessTaskName = `${androidSettings.headlessTaskName}${ranTaskCount}`;
  debug(`Generated headless task name: ${headlessTaskName}`);

  const initOptions = { ...androidSettings, headlessTaskName };
  const action = async (identifier: number) => {
    debug(`Action called with identifier: ${identifier}`);
    debug(`Current AppState: ${AppState.currentState}`);
    
    // Allow action to run in background
    debug(`Executing action with headlessTaskName: ${headlessTaskName}`);
    try {
      debug(`Calling act function with API parameters`);
      const api: ForegroundApi = {
        headlessTaskName,
        identifier
      };
      debug(`API object created:`, api);
      await act(api);
      debug(`Action completed successfully for identifier: ${identifier}`);
    } catch (error) {
      debug(`Action failed with error: ${error.message}`, error);
      console.error(`[ExpoForegroundActions] Action execution failed:`, error);
      throw error;
    }
  };
  
  debug(`Platform check: ${Platform.OS}`);
  if (Platform.OS !== "ios" && Platform.OS !== "android") {
    debug("Error: Unsupported platform");
    throw new Error("Unsupported platform, currently only ios and android are supported");
  }

  try {
    debug(`Incrementing ranTaskCount from ${ranTaskCount}`);
    ranTaskCount++;

    if (settings.runInJS === true) {
      debug(`Running in JS mode`);
      await runJS(action, settings);
      return;
    }
    if (Platform.OS === "android") {
      debug(`Running on Android platform`);
      /*On android we wrap the headless task in a promise so we can "await" the starter*/
      try {
        await runAndroid(action, initOptions, settings);
        debug(`runAndroid completed successfully`);
      } catch (androidError) {
        debug(`runAndroid failed with error: ${androidError.message}`, androidError);
        throw androidError;
      }
      return;
    }
    if (Platform.OS === "ios") {
      debug(`Running on iOS platform`);
      await runIos(action, settings);
      return;
    }
    debug(`Unsupported platform: ${Platform.OS}`);
    return;
  } catch (e) {
    debug(`runForegroundedAction caught error: ${e.message}`, e);
    console.error(`[ExpoForegroundActions] Error in runForegroundedAction:`, e);
    throw e;
  }
};


const runJS = async (action: (identifier: number) => Promise<void>, settings: Settings) => {
  debug(`Running in JS mode with current jsIdentifier: ${jsIdentifier}`);
  jsIdentifier++;
  debug(`Incremented jsIdentifier to: ${jsIdentifier}`);
  if (settings?.events?.onIdentifier) {
    debug(`Calling onIdentifier with jsIdentifier: ${jsIdentifier}`);
    settings.events.onIdentifier(jsIdentifier);
  }
  try {
    debug(`Executing action with jsIdentifier: ${jsIdentifier}`);
    await action(jsIdentifier);
    debug(`Action completed successfully in JS mode`);
  } catch (error) {
    debug(`Error executing action in JS mode: ${error.message}`, error);
    throw error;
  } finally {
    debug(`Resetting jsIdentifier to 0`);
    jsIdentifier = 0;
  }
};

const runIos = async (action: (identifier: number) => Promise<void>, settings: Settings) => {
  const identifier = await startForegroundAction();
  settings?.events?.onIdentifier?.(identifier);
  try {
    await action(identifier);
  } catch (e) {
    throw e;
  } finally {
    await stopForegroundAction(identifier);
  }
};

const runAndroid = async (action: (identifier: number) => Promise<void>, options: AndroidSettings, settings: Settings) => new Promise<void>(async (resolve, reject) => {
  debug("Running Android foreground action", { options });
  
  try {
    debug(`Registering headless task: ${options.headlessTaskName}`);
    /*First we register the headless task so we can run it from the Foreground service*/
    AppRegistry.registerHeadlessTask(options.headlessTaskName, () => async (taskdata: { notificationId: number }) => {
      const { notificationId } = taskdata;
      debug(`Headless task started with notificationId: ${notificationId}`);
      
      // Set up AppState listener to track state changes during execution
      let currentAppState = AppState.currentState;
      debug(`Initial AppState in headless task: ${currentAppState}`);
      
      const handleAppStateChange = (nextAppState: any) => {
        debug(`AppState changed from ${currentAppState} to ${nextAppState} during headless task`);
        currentAppState = nextAppState;
      };
      
      // Add the AppState listener
      // Different React Native versions handle event listeners differently
      AppState.addEventListener('change', handleAppStateChange);
      
      /*Then we start the actual foreground action, we do this in the headless task, without touching UI*/
      try {
        debug(`Calling onIdentifier event handler with ID: ${notificationId}`);
        if (settings?.events?.onIdentifier) {
          debug(`onIdentifier handler exists, calling it now`);
          settings.events.onIdentifier(notificationId);
        } else {
          debug(`No onIdentifier handler found`);
        }
        
        debug(`Executing action with ID: ${notificationId} (AppState: ${currentAppState})`);
        // Wrap the action call in a try/catch to catch any errors
        try {
          await action(notificationId);
          debug(`Action executed successfully`);
        } catch (actionError) {
          debug(`Error executing action: ${actionError.message}`, actionError);
          // Re-throw to be caught by outer catch
          throw actionError;
        }
        
        debug(`Action completed, stopping foreground service with ID: ${notificationId}`);
        await stopForegroundAction(notificationId);
        debug(`Android foreground action workflow completed successfully`);
        resolve();
      } catch (e) {
        debug(`Error in headless task: ${e.message}`, e);
        /*We do this to make sure its ALWAYS stopped*/
        debug(`Ensuring foreground service is stopped after error`);
        await stopForegroundAction(notificationId);
        reject(e);
      } finally {
        // Clean up the AppState listener
        // In newer versions of React Native, we would use subscription.remove()
        // In older versions, we use removeEventListener
        try {
          // For React Native >= 0.65
          AppState.removeEventListener('change', handleAppStateChange);
        } catch (error) {
          debug(`Error removing AppState listener: ${error}`);
        }
      }
    });
    
    debug(`Starting foreground service with options`, options);
    await startForegroundAction(options);
    debug(`Foreground service start initiated`);

  } catch (e) {
    debug(`Failed to run Android foreground action: ${e.message}`, e);
    reject(e);
    throw e;
  }
});

export const updateForegroundedAction = async (id: number, options: AndroidSettings) => {
  if (Platform.OS !== "android") return;
  return ExpoForegroundActionsModule.updateForegroundedAction(id, options);
};

// noinspection JSUnusedGlobalSymbols
export const stopForegroundAction = async (id: number): Promise<void> => {
  debug(`Stopping foreground action with ID: ${id}`);
  try {
    await ExpoForegroundActionsModule.stopForegroundAction(id);
    debug(`Successfully stopped foreground action with ID: ${id}`);
  } catch (error) {
    debug(`Failed to stop foreground action with ID: ${id}: ${error.message}`, error);
    throw error;
  }
};

// noinspection JSUnusedGlobalSymbols
export const forceStopAllForegroundActions = async (): Promise<void> => {
  await ExpoForegroundActionsModule.forceStopAllForegroundActions();
};

// noinspection JSUnusedGlobalSymbols
export const getForegroundIdentifiers = async (): Promise<number> => ExpoForegroundActionsModule.getForegroundIdentifiers();
// noinspection JSUnusedGlobalSymbols
export const getRanTaskCount = () => ranTaskCount;

export const getBackgroundTimeRemaining = async (): Promise<number> => {
  if (Platform.OS !== "ios") return -1;
  return await ExpoForegroundActionsModule.getBackgroundTimeRemaining();
};


export function addExpirationListener(
  listener: (event: ExpireEventPayload) => void
): Subscription {
  return emitter.addListener("onExpirationEvent", listener);
}

export { ExpireEventPayload };
