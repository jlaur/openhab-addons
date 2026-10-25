# Native Nest API authentication

This document records the tested browser capture procedure and the authentication interface for native Nest Protect support.
The native account bridge and Authenticate action are implemented alongside the SDM binding.
Live API behavior has been tested with a separate research probe; the Java implementation still needs validation in a running openHAB installation.

## Initial browser capture

Use the same normal browser profile and Google account for both captures.
Complete any Google login or two-factor authentication prompts in the browser.

1. Open <https://home.nest.com/> and sign in with the Google account that owns the Protects.
1. Open the browser developer tools and select **Network**.
   Enable **Preserve log**, then reload the page.
1. Filter requests for `issueToken`.
   Find a request to `https://accounts.google.com/o/oauth2/iframerpc` with `action=issueToken` in its query string.
   Verify that it succeeds and returns an access token.
1. Copy its complete **Request URL**, including the query string.
   A `:path` header alone is not the complete URL.
   Do not copy the response's access token or ID token: those expire and are not the credentials needed for renewal.
1. In the same browser profile, look for a successful **POST** to `https://accounts.google.com/RotateCookies`.
   The request may appear during normal Google session maintenance; it need not occur on every page reload.
   If necessary, also inspect the Network panel while visiting <https://accounts.google.com/> in the same profile.
1. Confirm HTTP status **200**, then open **Request Headers** for that request.
   Copy the complete **Cookie** header value, containing `name=value` pairs separated by semicolons.
   Copy the value only, without a `Cookie:` prefix or line breaks.
   Do not use response `Set-Cookie` headers, the request body, or the `:path` header.
1. Submit the URL and cookie header together using the authentication action below, soon after capture.

Treat both captured values as credentials.
Do not include them in screenshots, logs, issue reports, or chat messages.
The cookie capture can contain a broader Google browser session than access to Nest alone.

The working research capture came from a successful `RotateCookies` request.
A cookie header from another Google request is not a verified substitute: earlier captures could acquire an initial token but failed renewal or rotation.
If no successful rotation request is available, do not assume that a different header will work.

## Thing action

The `native_account` bridge exposes an **Authenticate** action using openHAB's `ThingActions` mechanism:

```text
authenticate(issueTokenUrl, cookieHeader)
```

Both arguments are strings obtained from the capture procedure.
Neither is a Thing configuration parameter.
The same action supplies replacement credentials when Google invalidates an existing session.

The action is callable while the bridge is waiting for authentication, including when its status is OFFLINE.
It validates the arguments and schedules authentication through the account handler.
Its immediate boolean result indicates only whether the request was accepted; the bridge status reports the eventual authentication result.
Reject malformed input without including its contents in errors.

The native account handler must:

- Accept only the HTTPS Google `issueToken` endpoint and refuse redirects when sending credentials.
- Use the supplied cookies to obtain a Google access token, exchange that for a Nest token, and establish the Nest session.
- Persist the current cookies and the URL through openHAB's `StorageService`, scoped to the account bridge UID.
- Save refreshed cookies when received, including updates accompanying an unsuccessful authentication response.
- Preserve cookie expiry, domain, path, and deletion semantics in the production implementation.
- Persist the last successful rotation time, preserving the rotation interval across restarts.
- Obtain fresh access tokens from persisted state after restart; short-lived access tokens need not be persisted.
- Keep credentials through ordinary disposal, disable/enable cycles, and binding upgrades; remove them when the account Thing is removed.
- Serialize credential replacement with ongoing authentication and polling so an obsolete task cannot overwrite newer credentials or publish stale status.
- Distinguish rejected credentials from temporary connection failures and rate limits.
  A transient failure must not immediately require another browser capture.

There must be no credential values in Thing properties, channels, action outputs, log messages, exception messages, or generated examples.
Ordinary `StorageService` storage does not automatically encrypt secrets.
The binding uses core's `StorageCipher` service to encrypt the credential DTO before storage and does not fall back to plaintext when encryption fails.

Using an action avoids retaining the original cookie capture in Thing configuration, but action arguments are not automatically ephemeral everywhere.
If a user invokes the action through a saved rule or script, that rule or script also retains the captured values.
Use the standard Thing action interface or REST actions endpoint described in the [binding README](../README.md#native-nest-protect-setup) for a one-time invocation.
Delete captured values from any temporary rule or script after use.

## Renewal and recovery

The research probe rotates Google cookies every ten minutes and renews access tokens before their effective expiry, approximately one hour in the observed sessions.
The Nest session response reports a longer expiry that did not establish the token's actual usable lifetime.
An immediate second rotation after a process restart returned HTTP 429, while authenticating with the saved cookies succeeded.
Preserve the rotation schedule across restarts and respect rate limiting rather than rotating on every initialization.

If Google rejects the stored session, repeat the browser capture and invoke the same authentication action with fresh values.
Keeping a browser open is not by itself evidence that previously captured cookies remain valid.

## Validation status

The research probe demonstrated:

- Authentication and discovery of two wired Nest Protects.
- Subscription updates for ordinary structure changes and a manually initiated sound check.
- A 70-minute run with cookie rotation, successful token renewal, and Protect updates after renewal.
- Authentication from a separate process using only persisted credentials, followed by a two-Protect snapshot and five minutes of subscription requests without authentication errors.

No device updates arrived during the five-minute restart observation, so that run did not independently establish update delivery after restart.
Persisting the rotation schedule is covered by offline tests but has not yet been validated in a live restart run.
Multi-day reliability, recovery after a long offline period, and behavior with other accounts remain unverified.

Runtime validation should verify action registration while unauthenticated, accepted and rejected input, asynchronous success and failure, replacement during an in-flight request, restart recovery, and cleanup on account removal.
