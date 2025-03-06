import {
    addExpirationListener,
    forceStopAllForegroundActions,
    getBackgroundTimeRemaining,
    getForegroundIdentifiers,
    runForegroundedAction,
    stopForegroundAction,
    updateForegroundedAction
  } from "expo-foreground-actions";
  import { ForegroundApi } from "expo-foreground-actions/build/ExpoForegroundActions.types";
  import { useEffect, useRef } from "react";
  import { Button, Linking, Platform, StatusBar, StyleSheet, View } from "react-native";
  
  Linking.addEventListener("url", onUrl);
  
  function onUrl(evt: any) {
    /*Here we check the deeplink URL*/
    console.log(evt.url);
  }
  
  /*Quick await*/
  function wait(ms: number) {
    return new Promise((resolve) => setTimeout(resolve, ms));
  }
  
  
  // Export the function so it can be accessed from other modules if needed
export const MyHelperFunction = async ({
                                    headlessTaskName,
                                    identifier
                                  }: ForegroundApi) => {
    console.log("[DEBUG] MyHelperFunction started with identifier:", identifier, "and headlessTaskName:", headlessTaskName);
    let time = Date.now();
    let duration = 0;
    while (duration < 50) {
      console.log("[DEBUG] Logging every 1 second... from foreground!", time, "duration:", duration);      
      duration += 1;
      try {
        await updateForegroundedAction(identifier, {
          headlessTaskName: headlessTaskName,
          notificationTitle: "Fittest",
          notificationDesc: "Fittest Workout ongoing",
          notificationColor: "#FFC107",
          notificationIconName: "ic_launcher",
          notificationIconType: "mipmap",
          notificationProgress: duration * 10,
          notificationMaxProgress: 100,
          notificationIndeterminate: false,   
          linkingURI: "myapp://"
        });
      } catch (error) {
        console.error("[ERROR] Failed to update foreground action:", error);
      }
      // Add a small delay between iterations
      await wait(1000);
    }
    console.log("[DEBUG] Logging interval ended.");
  };
  
  
  export default function Foreground() {
    const intervalRef = useRef<NodeJS.Timeout | null>(null);
    const currentRunningId = useRef<null | number>(null);
    useEffect(() => {
      /*Triggered on IOS expiration*/
      const sub = addExpirationListener((event) => {
        console.log(event);
      });
  
      return () => {
        sub && sub.remove();
      };
    }, []);
  
    useEffect(() => {
      /*We do this so we can see when app is backgrounded*/
      intervalRef.current = setInterval(() => {
        console.log("Logging every 1 second, so we can see when the app gets \"paused\"");
      }, 1000);
  
      return () => {
        intervalRef.current && clearInterval(intervalRef.current);
      };
    }, []);
    return (
      <View style={styles.container}>
        <StatusBar backgroundColor="#333333" />
        <Button title={"Get identifiers"} onPress={() => {
          getForegroundIdentifiers().then(r => console.log(r));
        }} />
        <Button
          title="Run a foreground action"
          onPress={async () => {
            try {
              console.log("[DEBUG] Button pressed, about to run foreground action");
              await runForegroundedAction(async (api) => {
                /*I added a helper function but you can do all logic in here*/
                console.log("[DEBUG] Action callback triggered with api:", api);
                await MyHelperFunction(api);
              }, {
                headlessTaskName: "create_task",
                notificationTitle: "Fittest",
                notificationDesc: "Fittest Workout ongoing",
                notificationColor: "#FFC107",
                notificationIconName: "ic_launcher",
                notificationIconType: "mipmap",
                notificationProgress: 1,
                notificationMaxProgress: 100,
                notificationIndeterminate: false,
                linkingURI: "myapp://"
              }, {
                events: {
                  onIdentifier: (id) => {
                    if (!currentRunningId.current) currentRunningId.current = id;
                    console.log("Identifier:", id);
                  }
                }
              });
            } catch (e: any) {
              console.log("Error:", e?.message);
              throw e;
            }
          }}
        />
        <Button title={"Stop current running foreground action"} onPress={async () => {
          if (currentRunningId.current) {
            console.log("Stopping foreground action with id:", currentRunningId.current);
            await stopForegroundAction(currentRunningId.current);
            currentRunningId.current = null;
            console.log("Stopped foreground action");
          } else {
            console.log("No running foreground action");
          }
        }} />
        <Button title={"Force stop all running foreground actions"} onPress={async () => {
          await forceStopAllForegroundActions();
        }} />
      </View>
    );
  }
  
  const styles = StyleSheet.create({
    container: {
      flex: 1,
      backgroundColor: "#333333",
      alignItems: "center",
      justifyContent: "center"
    }
  });