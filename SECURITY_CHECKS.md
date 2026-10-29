# Security verification

The desktop shared call token, phone number and SMS message stay in the current form only. They are never saved in localStorage. Loading saved state removes these legacy private fields while preserving the receiver IP and port. The token is masked; reloading requires the private fields to be entered again.

Run `npm ci`, `npm test`, `npm run build` and `npm audit` from `desktop/` using Node 22.22.2, 24.15 or newer supported release. Two DOM regression tests load the actual HTML and application module. They verify legacy-state cleanup, IP/port restoration, masked token entry, private values staying usable in the form, idle controls and no phone/SMS requests. Both tests failed against the old persistence logic. Earlier isolated Chrome checks independently verified the token-only migration and reload; the final DOM suite covers all three private fields. A physical phone, call, SMS or FCM delivery was not exercised.

## Android security analysis build

The official Gradle 9.8.2 wrapper pins its distribution SHA-256, and its wrapper JAR is compared with the official published checksum. GitHub CodeQL previously generated an incompatible Gradle 8.6 wrapper, so Android analysis failed and the language was dropped from default setup. The checked-in wrapper supplies the required build version. Local JDK 17 verification runs `./gradlew --no-daemon assembleDebug lint test`. Four JVM tests use the actual HTTP receiver on a loopback socket: empty and legacy public configured tokens are rejected, missing and wrong headers cannot trigger any of the four routes, and an explicit configured token invokes one fixture callback. The original receiver failed the empty/public-token cases. These tests never call Android telephony or SMS services. Confirm a successful Java/Kotlin analysis at the final default-branch SHA after integration.

## Shared token and FCM command

The Android receiver starts only after a private shared token is configured. The HTTP and FCM entry points apply the same token check, and the old public `dev-token` value cannot be used. Existing private saved tokens remain available; the Android input masks them.

Run `node --test tools/fcm-security.test.mjs` from the repository root. Node 24 and 26 pass four tests each. The CLI rejects a missing or legacy public token before any fetch attempt. The accepted fixture path creates a temporary RSA key, verifies the real RS256 OAuth assertion, and checks the exact Google token and FCM request URLs through an intercepted fetch. It sends no request to Google and no message to a device. Temporary credentials are removed after the test.
