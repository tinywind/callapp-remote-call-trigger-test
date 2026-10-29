# Security verification

The desktop shared call token stays in the current form only. It is masked and never saved in localStorage. Loading saved state removes a legacy token while preserving the other form fields. Reloading clears the token and requires it to be entered again.

To verify this boundary, load the local desktop page, seed `remote-call-trigger-form` with a legacy `callToken` and the other form fields, and reload. The token input must be empty and the saved JSON must omit `callToken`. Enter a new token: it stays in the input and remains absent from saved JSON. Reload once more and confirm it is empty. Do not submit a call or SMS during this check.

Local isolated Chrome checks verify migration, masked input, memory-only entry, unchanged other fields, reload, idle controls, and no call/SMS requests. The frontend production build also passes. A physical phone, call, SMS, or FCM delivery was not exercised.
