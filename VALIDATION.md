# Remote Call Trigger Test validation

Last validated: 2026-05-13, Asia/Seoul.

Recipient phone numbers and device serials are represented by placeholders.

## Completed checks

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
- Hang up support was added through `TelecomManager.endCall()` with the
  `ANSWER_PHONE_CALLS` runtime permission.
- With no active call, `POST /hangup` returned `409 Conflict` and app state
  recorded `TelecomManager.endCall found no active call to end.`
- During an active outgoing S23 test call, `POST /hangup` returned `202
  Accepted`, app state recorded `TelecomManager.endCall ended the active call`,
  and S23 Telecom logs showed `REQUEST_DISCONNECT` from
  `com.example.remotecallreceiver` followed by `LOG_DISCONNECTED_CALL`.
- FCM `action=hangup` was accepted by Firebase Cloud Messaging. ADB log capture
  dropped immediately after that send, so direct Telecom log confirmation for
  the FCM hang up path is still pending. A follow-up LAN `POST /hangup` returned
  `409 Conflict`, which means no active call remained by then.
- The actual Tauri desktop WebView demo button was run against the S23. The app
  sent `/call`, waited three seconds after the call request was accepted, sent
  `/hangup`, and showed `Demo complete. Call ended after 3 seconds.`
- In that desktop demo run, `/call` returned `202 Accepted`, `/hangup` returned
  `202 Accepted`, the measured gap after `/call` acceptance was about 3006 ms,
  and a follow-up `/hangup` returned `409 Conflict`.
- SMS send support was added through `SmsManager` with the `SEND_SMS` runtime
  permission.
- SMS backup support was added through the Android SMS provider with the
  `READ_SMS` runtime permission.
- The first SMS smoke test used `192.168.8.107:43769`, which was later
  confirmed through ADB as `TB322FC` / `<other-device-serial>`, not the S23.
- The correct S23 target was confirmed as `SM-S911N` / `<s23-device-serial>` with Wi-Fi
  IP `192.168.8.246`.
- The Android debug APK was rebuilt and installed on the wirelessly connected
  S23 after the SMS changes.
- The Android receiver screen now reports the Wi-Fi IP as
  `192.168.8.246`.
- Direct LAN `POST /sms/backup` returned `200 OK` JSON from the S23 receiver
  with `count=7283`.
- SMS send result tracking now records the Android radio result through a
  `PendingIntent` broadcast receiver instead of only reporting that
  `SmsManager` was called.
- With the Android Activity in the background and Samsung Launcher focused,
  `POST /sms/send` to recipient `<test-recipient>` returned `202 Accepted`.
- In that background SMS test, the Android radio result callback recorded
  `SMS sent to <test-recipient>.` in the receiver state.
- After that send, direct LAN `POST /sms/backup` returned `200 OK` JSON from
  the S23 receiver with `count=7285`, and the latest row for `<test-recipient>`
  contained `remote sms background test 2026-05-13 17:03 KST`.

## Current conclusion

- A powered-off phone cannot receive the request or place a call.
- A powered-on, network-connected S23 can receive the desktop request while the
  screen is off if the foreground receiver service is already running.
- A powered-on, network-connected S23 can also be woken by FCM when the app
  process is gone and the receiver service is stopped, as long as Android has
  not force-stopped the app.
- The same app can request ending the foreground call with
  `TelecomManager.endCall()` when `ANSWER_PHONE_CALLS` is granted. This was
  confirmed through the LAN `/hangup` endpoint.
- The same app can request SMS sending and SMS JSON backup from the desktop
  app when `SEND_SMS` and `READ_SMS` are granted.
- SMS sending and backup were confirmed while the Android Activity was in the
  background, as long as `CallReceiverService` remained active as a foreground
  service.
- Direct background `ACTION_CALL` activity launch is not reliable on modern
  Android/Samsung firmware.
- `TelecomManager.placeCall()` is the working path for this proof of concept.

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
