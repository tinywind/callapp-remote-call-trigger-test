# Remote Call Trigger Test

This repository contains a minimal proof of concept for triggering a real phone
call from a desktop app through an Android phone on the same network.

Current flow:

1. The desktop Tauri app sends `POST /call` to the Android phone IP.
2. The Android foreground service validates the shared token and phone number.
3. The Android service requests an outgoing call through `TelecomManager.placeCall()`.
4. The service stays alive through a foreground notification and a battery
   optimization whitelist during local testing.
5. If the LAN receiver is not running, FCM high-priority data messages can ask
   the app to start the foreground service and place the call.

This is for local testing only. It does not replace a dialer, Phone Link, or a
production call-control integration.

## Safety Notes

- The Android app can place real outgoing calls after the `CALL_PHONE`
  permission is granted.
- Test only with numbers you control.
- Do not use emergency, premium-rate, or third-party numbers for validation.
- Keep the Android foreground service notification running during testing.
- The shared token is only a local guard for this proof of concept. Do not
  expose the Android endpoint outside a trusted LAN.
- `google-services.json` is intentionally ignored. Do not commit Firebase
  project credentials to this repository.
- Firebase service account JSON files are intentionally ignored. Store them
  outside the repository and point `GOOGLE_APPLICATION_CREDENTIALS` to that
  local path.

## Project Layout

- `desktop/`: Tauri desktop client.
- `android/`: Native Android receiver app for the Samsung phone.

## Android Receiver

Open `android/` in Android Studio, install it on the Samsung phone, and grant
the Phone and Notification permissions when prompted.

Default receiver settings:

- Port: `8088`
- Token: `dev-token`
- Endpoint: `POST http://PHONE_IP:8088/call`

The request body must be JSON:

```json
{
  "number": "+821012345678"
}
```

The request must include:

```text
X-Call-Token: dev-token
Content-Type: application/json
```

The receiver can also be started over ADB for validation:

```powershell
adb shell am start-foreground-service `
  -n com.example.remotecallreceiver/.CallReceiverService `
  --ei port 8088 `
  --es token dev-token
```

## Automatic Recovery Paths

The app now includes three recovery paths:

- `START_STICKY`: if Android kills the already-running service for memory
  pressure, the system may recreate it and the service reuses the last saved
  port/token.
- Boot/package receiver: `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED` request a
  receiver service restart. Modern Android may still block foreground service
  startup from boot for some service types, so failures are recorded in the app
  status instead of being treated as guaranteed recovery.
- FCM high-priority data message: if the app process is not alive, Firebase can
  wake it for a short handler window. The FCM handler validates the same token,
  starts the foreground service, and passes the number to
  `TelecomManager.placeCall()`.

To enable FCM, add the Firebase Android app configuration file locally:

```text
android/app/google-services.json
```

Then use the **Show FCM Token** button in the Android app and register that
token with the server that will send wake messages.

The service account key used by `tools/send-fcm-call.mjs` should be stored
outside the repository, for example:

```text
C:\Users\tinywind\.secrets\callapp-remote-call-test\firebase-service-account.json
```

Expected FCM data payload:

```json
{
  "action": "call",
  "token": "dev-token",
  "number": "+821012345678",
  "port": "8088"
}
```

The FCM message should use Android high priority. Normal-priority messages may
be delayed in Doze and may not be allowed to start a foreground service.

None of these recovery paths can run while the phone is powered off. Android
also blocks background work for force-stopped apps until the user launches the
app again.

For local testing from this repository, use the helper script with a Firebase
service account JSON file stored outside Git:

```powershell
$env:GOOGLE_APPLICATION_CREDENTIALS="C:\path\to\service-account.json"
node tools/send-fcm-call.mjs `
  --device-token "<android-fcm-token>" `
  --number "+821012345678" `
  --call-token "dev-token" `
  --port "8088"
```

## Desktop Client

From `desktop/`, install dependencies and run the Tauri app:

```powershell
npm install
npm run tauri:dev
```

Enter the Samsung phone IP, port, token, and target number, then trigger the
call.

## Manual HTTP Check

You can also send a direct request from another client:

```powershell
Invoke-RestMethod `
  -Method Post `
  -Uri "http://PHONE_IP:8088/call" `
  -Headers @{ "X-Call-Token" = "dev-token" } `
  -ContentType "application/json" `
  -Body '{"number":"+821012345678"}'
```

## Validation Status

Last validated: 2026-05-13, Asia/Seoul.

Completed checks:

- Android debug APK builds successfully with `:app:assembleDebug`.
- Tauri frontend builds successfully with `npm run build`.
- Tauri Windows app builds successfully with `npm run tauri:build`.
- Generated artifacts were excluded from Git through `.gitignore`.
- WSA install and launch were verified through ADB at `127.0.0.1:58526`.
- WSA receiver accepted `POST /call` and reached the Android call intent path.
- Samsung S23 `SM-S911N` wireless ADB pairing and install were verified.
- S23 receiver accepted desktop requests on `http://192.168.8.246:8088/call`.
- With the initial `Intent.ACTION_CALL` implementation, screen-off/background
  calls were blocked by Android background activity launch policy
  (`Background activity launch blocked`, `BAL_BLOCK`).
- The Android receiver was changed to a foreground service using
  `TelecomManager.placeCall()`.
- With the phone screen off and `mWakefulness=Dozing`, the desktop request was
  accepted and S23 Telecom logs showed:
  - `placeCall`
  - `processOutgoingCallIntent`
  - call state `CONNECTING`
  - call state `DIALING`
  - Samsung `InCallActivity` launch
- The test call was then terminated with `KEYCODE_ENDCALL`.
- Firebase project setup was completed for the Android package
  `com.example.remotecallreceiver`.
- `google-services.json` was installed locally and is ignored by Git.
- A local Firebase service account JSON was created for the FCM test sender and
  is ignored by Git.
- The Android debug APK was rebuilt with Firebase Messaging and installed on
  the S23.
- The Android app generated an FCM registration token through Firebase
  Messaging.
- While the receiver service was already running, an FCM high-priority data
  message was accepted and the S23 Telecom logs showed the outgoing call flow.
- With the app process absent and the receiver service stopped, but without
  Android force-stop, an FCM high-priority data message woke the app, started
  `CallReceiverService`, and requested `TelecomManager.placeCall()`.
- In that killed-process FCM test, app state changed back to `running=true` and
  S23 Telecom logs showed `START_OUTGOING_CALL`, `SET_CONNECTING`,
  `PLACE_OUTGOING_CALL`, `SET_DIALING`, and `LOG_DISCONNECTED_CALL`.

Current conclusion:

- A powered-off phone cannot receive the request or place a call.
- A powered-on, network-connected S23 can receive the desktop request while the
  screen is off if the foreground receiver service is already running.
- A powered-on, network-connected S23 can also be woken by FCM when the app
  process is gone and the receiver service is stopped, as long as Android has
  not force-stopped the app.
- Direct background `ACTION_CALL` activity launch is not reliable on modern
  Android/Samsung firmware.
- `TelecomManager.placeCall()` is the working path for this proof of concept.

Known limits:

- This is not a production dialer integration.
- LAN HTTP triggering depends on local LAN reachability, foreground service
  survival, granted permissions, and Samsung/Android call policy.
- FCM wake triggering depends on Google Play services, Firebase delivery,
  granted permissions, and Samsung/Android foreground service policy.
- FCM cannot wake the app after the user or system force-stops it. The user must
  launch the app again before FCM delivery can resume.
- The token is a development guard only and is not enough for an exposed network.
