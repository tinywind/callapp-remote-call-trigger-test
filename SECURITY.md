# Security Policy

## Supported Versions

Security fixes are applied on the `main` branch of `callapp-remote-call-trigger-test` across both components (`desktop/` Tauri app and `android/` receiver app). Older commits are not backported.

| Version | Supported |
| ------- | --------- |
| `main` (latest commit) | Yes |
| Historical commits | No |

## Reporting a Vulnerability

Please do **not** report undisclosed security vulnerabilities through public GitHub issues, pull requests, or discussions.

Use GitHub's [private vulnerability reporting](https://github.com/tinywind/callapp-remote-call-trigger-test/security/advisories/new) for this repository to submit a report confidentially. If you cannot use GitHub Security Advisories, contact the maintainer directly at `tinywind0@gmail.com`.

When reporting an issue, please include as much of the following as possible:

- Affected component (`desktop/` Tauri client, `android/` foreground service, or `tools/`) and commit SHA
- Platform details (Windows/desktop OS, Node.js/Rust version, or Android SDK version)
- A minimal reproduction or proof of concept (never include real phone numbers, Firebase credentials, `google-services.json`, or shared tokens)
- Description of the potential impact and any suggested mitigation

## Response Process

- We aim to acknowledge receipt of a vulnerability report within 7 days.
- Validated issues are tracked privately through a GitHub Security Advisory while a fix is prepared and verified.
- Once a patch is available on `main`, we will publish the advisory and credit reporters who wish to be acknowledged.

## Scope and Security Model Notes

`callapp-remote-call-trigger-test` is a local-network proof of concept for triggering outgoing phone calls, hangup, SMS send, and SMS backup from a desktop Tauri client through an Android foreground service (optionally woken via FCM data messages). See also [SECURITY_CHECKS.md](SECURITY_CHECKS.md) for existing security regression checks.

### In scope

- Authentication or shared-token bypasses on the Android receiver endpoints (`/call`, `/hangup`, `/sms/send`, `/sms/backup`)
- Accidental persistence or leakage of the shared token, phone number, or SMS body in desktop storage (`localStorage` or logs)
- Input validation, phone-number sanitization, or command/intent injection bugs in `desktop/` or `android/`
- Vulnerabilities in bundled or transitive npm, Cargo, or Gradle dependencies affecting `main`

### Out of scope

- Physical device access or compromised local networks where an operator intentionally exposes the LAN receiver outside a trusted LAN
- Misuse of granted Android telephony/SMS permissions (`CALL_PHONE`, `ANSWER_PHONE_CALLS`, `SEND_SMS`, `READ_SMS`) by an already authorized caller holding the valid shared token
- Operator-supplied Firebase credentials (`google-services.json` or `GOOGLE_APPLICATION_CREDENTIALS`) managed outside the repository
