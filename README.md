# Remote Call Trigger Test

This repository contains a minimal proof of concept for triggering a real phone
call from a desktop app through an Android phone on the same network.

Current flow:

1. The desktop Tauri app sends `POST /call` to the Android phone IP.
2. The Android foreground service validates the shared token and phone number.
3. The Android service requests an outgoing call through `TelecomManager.placeCall()`.
4. The service stays alive through a foreground notification and a battery
   optimization whitelist during local testing.

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

Current conclusion:

- A powered-off phone cannot receive the request or place a call.
- A powered-on, network-connected S23 can receive the desktop request while the
  screen is off if the foreground receiver service is already running.
- Direct background `ACTION_CALL` activity launch is not reliable on modern
  Android/Samsung firmware.
- `TelecomManager.placeCall()` is the working path for this proof of concept.

Known limits:

- This is not a production dialer integration.
- The receiver still depends on local LAN reachability, foreground service
  survival, granted permissions, and Samsung/Android call policy.
- The token is a development guard only and is not enough for an exposed network.
