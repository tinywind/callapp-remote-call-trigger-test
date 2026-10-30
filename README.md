# Remote Call Trigger Test

This repository contains a minimal proof of concept for triggering a real phone
call from a desktop app through an Android phone on the same network.

Current flow:

1. The desktop Tauri app sends `POST /call`, `POST /hangup`, `POST /sms/send`,
   or `POST /sms/backup` to the Android phone IP.
2. The Android foreground service validates the shared token and validates the
   phone number when the request places a call.
3. The Android service requests an outgoing call through
   `TelecomManager.placeCall()` or requests ending the active call through
   `TelecomManager.endCall()`.
4. The Android service can send SMS through `SmsManager` and export SMS rows
   from the phone SMS provider as a JSON backup for the PC.
5. The service stays alive through a foreground notification and a battery
   optimization whitelist during local testing.
6. If the LAN receiver is not running, FCM high-priority data messages can ask
   the app to start the foreground service and place the call.

This is for local testing only. It does not replace a dialer, Phone Link, or a
production call-control integration.

## Safety notes

- The Android app can place real outgoing calls after the `CALL_PHONE`
  permission is granted.
- The Android app can request ending the active call after the
  `ANSWER_PHONE_CALLS` permission is granted.
- The Android app can send SMS after `SEND_SMS` is granted and export SMS
  backup data after `READ_SMS` is granted.
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

- The desktop token, phone number and SMS message stay in the current window
  and are never saved in browser storage. Loading an older saved form removes
  these private fields while preserving the receiver IP and port. See [security
  verification](SECURITY_CHECKS.md).

## Project layout

- `desktop/`: Tauri desktop client.
- `android/`: Native Android receiver app for the Samsung phone.

## Android receiver

Install JDK 17 and Android SDK Platform 36 through Android Studio. The receiver
declares a minimum Android SDK level of 26 and requires telephony hardware.

Build with the checked-in Gradle 9.8.2 wrapper: run
`./gradlew assembleDebug` from `android/` (`gradlew.bat` on Windows). AGP 9
provides Kotlin support directly, so the separate Kotlin Android plugin is no
longer applied.

Open `android/` in Android Studio, install it on the Samsung phone, and grant
the Phone and Notification permissions when prompted.

Default receiver settings:

- Port: `8088`
- Token: empty until you configure a private shared value
- Endpoint: `POST http://PHONE_IP:8088/call`
- Hang up endpoint: `POST http://PHONE_IP:8088/hangup`
- Send SMS endpoint: `POST http://PHONE_IP:8088/sms/send`
- SMS backup endpoint: `POST http://PHONE_IP:8088/sms/backup`

Set the same private shared token in the Android app and each client before starting the receiver. An empty token and the old public `dev-token` value are rejected, including a legacy saved value. Generate a value locally, for example with `openssl rand -hex 32`, and replace `<private-shared-token>` in the examples below. Keep the generated value out of Git.

The request body must be JSON:

```json
{
  "number": "+821012345678"
}
```

The request must include:

```text
X-Call-Token: <private-shared-token>
Content-Type: application/json
```

The hang up request uses the same token header and does not require a body:

```powershell
Invoke-RestMethod `
  -Method Post `
  -Uri "http://PHONE_IP:8088/hangup" `
  -Headers @{ "X-Call-Token" = "<private-shared-token>" }
```

The SMS send request uses the same token header:

```powershell
Invoke-RestMethod `
  -Method Post `
  -Uri "http://PHONE_IP:8088/sms/send" `
  -Headers @{ "X-Call-Token" = "<private-shared-token>" } `
  -ContentType "application/json" `
  -Body '{"number":"+821012345678","message":"hello from pc"}'
```

The SMS backup request returns JSON with every row exposed by the phone SMS
provider:

```powershell
Invoke-RestMethod `
  -Method Post `
  -Uri "http://PHONE_IP:8088/sms/backup" `
  -Headers @{ "X-Call-Token" = "<private-shared-token>" } `
  -OutFile ".\sms-backup.json"
```

The receiver can also be started over ADB for validation:

```powershell
adb shell am start-foreground-service `
  -n com.example.remotecallreceiver/.CallReceiverService `
  --ei port 8088 `
  --es token "<private-shared-token>"
```

## Automatic recovery paths

The app includes three recovery paths:

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

Then use the `Show FCM Token` button in the Android app and register that
token with the server that will send wake messages.

The service account key used by `tools/send-fcm-call.mjs` should be stored
outside the repository, for example:

```text
C:\Users\tinywind\.secrets\callapp-remote-call-test\firebase-service-account.json
```

Expected FCM call data payload:

```json
{
  "action": "call",
  "token": "<private-shared-token>",
  "number": "+821012345678",
  "port": "8088"
}
```

Expected FCM hang up data payload:

```json
{
  "action": "hangup",
  "token": "<private-shared-token>",
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
  --call-token "<private-shared-token>" `
  --port "8088"
```

For FCM hang up testing, use:

```powershell
node tools/send-fcm-call.mjs `
  --device-token "<android-fcm-token>" `
  --action "hangup" `
  --call-token "<private-shared-token>" `
  --port "8088"
```

## Desktop client

Install Rust and the native dependencies for your operating system using the
[Tauri 2 prerequisites](https://v2.tauri.app/start/prerequisites/). On Windows,
this includes the Microsoft C++ Build Tools and WebView2.

The desktop toolchain supports Node.js `^22.22.2 || ^24.15.0 || >=26.0.0`, as
specified in [`desktop/package.json`](desktop/package.json). From `desktop/`,
install the locked dependencies and run the Tauri app:

```powershell
npm ci
npm run tauri:dev
```

Enter the Samsung phone IP, port, token, and target number. The desktop app can
trigger a call, send a manual hang up request, or run the demo action that
places the call and sends hang up three seconds after the call request is
accepted. It can also send an SMS to the number field and download a JSON SMS
backup from the phone.

## Manual HTTP check

You can also send a direct request from another client:

```powershell
Invoke-RestMethod `
  -Method Post `
  -Uri "http://PHONE_IP:8088/call" `
  -Headers @{ "X-Call-Token" = "<private-shared-token>" } `
  -ContentType "application/json" `
  -Body '{"number":"+821012345678"}'
```

## Validation status

Hardware validation recorded: 2026-05-13, Asia/Seoul. The
[validation record](VALIDATION.md) preserves the completed checks and pending
observations; it is not a new validation of the current toolchain.

- LAN calls worked on a Samsung S23 with the screen off while the foreground
  receiver service was running.
- FCM woke a stopped receiver process and requested a call when the app had not
  been force-stopped.
- LAN hang up, the desktop three-second call demo, background SMS sending and
  SMS JSON backup were confirmed.
- Direct Telecom log confirmation for the FCM hang up path remains pending.

## Known limits

- This is not a production dialer integration.
- LAN HTTP triggering depends on local LAN reachability, foreground service
  survival, granted permissions, and Samsung/Android call policy.
- FCM wake triggering depends on Google Play services, Firebase delivery,
  granted permissions, and Samsung/Android foreground service policy.
- FCM cannot wake the app after the user or system force-stops it. The user must
  launch the app again before FCM delivery can resume.
- `TelecomManager.endCall()` is available for this test path but is deprecated
  from Android 10. A production dialer or companion-device implementation should
  use the appropriate Telecom role/service APIs instead.
- The token is a development guard only and is not enough for an exposed network.
