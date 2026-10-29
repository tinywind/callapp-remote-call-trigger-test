# Security verification

The desktop shared call token stays in the current form only. It is masked and never saved in localStorage. Loading saved state removes a legacy token while preserving the other form fields. Reloading clears the token and requires it to be entered again.

To verify this boundary, load the local desktop page, seed `remote-call-trigger-form` with a legacy `callToken` and the other form fields, and reload. The token input must be empty and the saved JSON must omit `callToken`. Enter a new token: it stays in the input and remains absent from saved JSON. Reload once more and confirm it is empty. Do not submit a call or SMS during this check.

Local isolated Chrome checks verify migration, masked input, memory-only entry, unchanged other fields, reload, idle controls, and no call/SMS requests. The frontend production build also passes. A physical phone, call, SMS, or FCM delivery was not exercised.

## Android security analysis build

The official Gradle 9.8.2 wrapper pins its distribution SHA-256, and its wrapper JAR is compared with the official published checksum. GitHub CodeQL previously generated an incompatible Gradle 8.6 wrapper, so Android analysis failed and the language was dropped from default setup. The checked-in wrapper supplies the required build version. Local JDK 17 verification runs `./gradlew --no-daemon assembleDebug lint test`. Four JVM tests use the actual HTTP receiver on a loopback socket: empty and legacy public configured tokens are rejected, missing and wrong headers cannot trigger any of the four routes, and an explicit configured token invokes one fixture callback. The original receiver failed the empty/public-token cases. These tests never call Android telephony or SMS services. Confirm a successful Java/Kotlin analysis at the final default-branch SHA after integration.

## Shared token and FCM command

The Android receiver starts only after a private shared token is configured. The HTTP and FCM entry points apply the same token check, and the old public `dev-token` value cannot be used. Existing private saved tokens remain available; the Android input masks them.

Run `node --test tools/fcm-security.test.mjs` from the repository root. Node 24 and 26 pass four tests each. The CLI rejects a missing or legacy public token before any fetch attempt. The accepted fixture path creates a temporary RSA key, verifies the real RS256 OAuth assertion, and checks the exact Google token and FCM request URLs through an intercepted fetch. It sends no request to Google and no message to a device. Temporary credentials are removed after the test.
