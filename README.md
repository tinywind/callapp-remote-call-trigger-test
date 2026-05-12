# Remote Call Trigger Test

This repository contains a minimal proof of concept for triggering a real phone
call from a desktop app through an Android phone on the same network.

Flow:

1. The desktop Tauri app sends `POST /call` to the Android phone IP.
2. The Android app validates the shared token and phone number.
3. The Android app starts an `Intent.ACTION_CALL` request with the `tel:` URI.

This is for local testing only. It does not replace a dialer, Phone Link, or a
production call-control integration.

## Safety Notes

- The Android app can place real outgoing calls after the `CALL_PHONE`
  permission is granted.
- Test only with numbers you control.
- Do not use emergency, premium-rate, or third-party numbers for validation.
- Keep the Android receiver app open during testing. The simple receiver does
  not run as a foreground service.
- The shared token is only a local guard for this proof of concept. Do not
  expose the Android endpoint outside a trusted LAN.

## Project Layout

- `desktop/`: Tauri desktop client.
- `android/`: Native Android receiver app for the Samsung phone.

## Android Receiver

Open `android/` in Android Studio, install it on the Samsung phone, and grant
the Phone permission when prompted.

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
