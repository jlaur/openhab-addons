# Danfoss Link Development and Test Notes

User-facing setup and operation are documented in [README.md](README.md).
These notes collect protocol observations, implementation details and live-test follow-up.

## Protocol and compatibility

The implementation is based on the reverse-engineered Android app protocol and has been developed against controller firmware 4.2.1724.

## Pairing and persistence

The binding generates its own app identity and persists its private key and paired controller certificate using openHAB's StorageService, keyed by Bridge UID.

## Temperature handling

The observed protocol value is `Celcius`.
The binding also recognizes `Fahrenheit`, although this representation has not yet been verified against a controller configured to use Fahrenheit.

The four room setpoint channels expose the controller-reported room limits as dynamic state-description minimum and maximum values.
Their display pattern uses the controller's temperature unit so the bounds and displayed values use the same scale.
The bounds are refreshed when the controller reports changes; no step size is inferred from them.

## Write handling

The binding reads the controller again after a write to publish its reported state.
It does not automatically replay a failed write because the controller may already have applied it.

Manual-mode writes refresh all rooms afterwards, because a member room can change the mode of the entire Living zone.

## Alarms

The Bridge reports `allUnresolvedAlarms` from each normal world refresh, without an additional cloud request.

Missing, malformed or unavailable alarm data publishes `UNDEF` to all three channels; malformed alarm data does not interrupt heating updates.

The `alarms` channel preserves the controller's JSON fields, including message identifiers and unknown parameters.
Message identifiers are not yet translated into readable descriptions, and non-empty payloads still need live verification.

## Current state

The controller channel `current-state` reports `systemStatus.currentState` from the controller, without evaluating schedules locally.

If the controller reports a comma-separated composite state, the binding uses its first component, matching the app.

The `current-state` and `home` channels are updated from controller readback, without deriving one from the other.

## Home override

Room identifiers are resolved by the binding; users do not supply them.

Sending ON to `home` again while Home is active submits the revised staged selection.
This repeated activation still needs verification on the controller.

Send OFF to cancel Home through its own mode endpoint, retaining the staged selection.

Failed writes are not replayed automatically; controller rejections are logged at DEBUG and followed by readback.
TRACE logging includes the Home request for troubleshooting.

## Controller clock conversion

The offset is calculated separately for each date, so planned periods can cross daylight-saving transitions.
For each world response, the binding estimates clock skew by comparing the controller's `systemTime` with openHAB time when the response is received.
Outgoing deadlines are shifted into controller time; incoming mode dates are shifted back into openHAB time before validation or publication.
This also applies to vacations configured outside openHAB and to edits of planned vacation dates.
Staged vacation dates stay in openHAB time until sent using a fresh controller response.

The estimate is refreshed for every response and is not retained across connections.
Network latency and the controller's timestamp precision can cause small differences in readback.
If a response has no usable `systemTime`, dates remain unadjusted for that response.
The `systemTime` property and clock-skew warning remain uncorrected; the binding does not change the controller clock.

## Pause

The channels publish controller readback rather than assuming commands succeeded.

Commands rejected by the controller are logged at DEBUG and followed by a refresh; failed writes are not retried automatically.
The controller manages expiration, so openHAB does not schedule a separate cancellation.

A live test confirmed a custom Pause deadline and the subsequent clock-skew correction.
For regression testing, send a deadline five minutes in the future, check the returned `pause-until`, and confirm that `pause` returns to OFF after expiration (allowing for the polling interval).
A successful request alone does not prove that the controller honors the requested duration.

TRACE logging includes the outgoing Pause request for comparison with readback.

## Vacation

The `vacation-temperature` setting is read back after a write; missing values or an unknown scale produce `UNDEF`.

Mode requests use controller-local `LocalDateTime` values with whole-second precision.
Clock conversion happens before populating the request DTO, using the configured timezone and measured controller clock skew.

Accepted dates are refreshed from the controller, including any normalization.

## Living zone

The controller's world response reports regulation on the member rooms rather than a separate zone object.
The zone's manual state is therefore derived from its members: all must report Manual for ON or Schedule for OFF.

The Living-zone Thing's `manual` switch uses the same zone command as the manual switch of a member room.

## Schedules

Living-zone Things and member rooms read through the lowest-ID Living-zone member in the current world response.
Consumers using that same member share a single detailed room request within the poll.

Detailed room and shared Living-zone schedule reads have been verified on a live controller.
TRACE logging includes the response to `GET /api/rooms/{roomId}`.
Users do not need to supply room IDs to fetch schedules.

Independent rooms use their own endpoint; member rooms and the Living-zone Thing use the shared zone endpoint.

Already-sent commands are not merged or replayed.

The schedule channel vetoes autoupdate and publishes controller readback after a write, including after an HTTP rejection.

A full-week independent-room schedule write was confirmed by a successful response and subsequent detailed-room readback.
Shared Living-zone writes, preservation of omitted weekdays and acceptance of `Asleep` in weekly writes still require live validation.
For further live testing, save the complete reported week, change a single weekday, check readback and the app/controller (including untouched days), then restore the saved schedule.
A graphical schedule editor is not yet provided.

## Regulation state

The Android app defines the regulation values documented in the README; other firmware versions may report additional values.

The app displays its heating indicator only for `Heating`.

`Deadband` has also been observed during Pause.

`ForecastHeating` apparently denotes predictive/preheating operation; its precise meaning has not been verified.

`Invalid` is the app's fallback for missing or unrecognized values; it is not necessarily sent by the controller.

## Transport and certificates

The binding includes the public MDG CA certificate extracted from the Android app.
No APK, external certificate file, Python installation, or Java security configuration changes are required.
Cloud certificates are validated against this trust anchor and the MDG control service identity.
Controller certificates are authenticated during PACE pairing and pinned for subsequent connections.

Bouncy Castle is provided by shared OSGi bundles for the legacy TLS and PACE protocols.
These dependencies are installed automatically when installing the binding feature.
The controller uses an ECDSA certificate with explicit Brainpool P320r1 parameters, and the original TLS implementation uses a legacy supported-group identifier.
Enabling `TLS_RSA_*` suites does not resolve these EC certificate and protocol compatibility issues.
The binding keeps this compatibility handling local rather than changing global JVM security settings.

## Diagnostics

The log records commands being applied, request URIs, write bodies, response HTTP status, and bounded controller error details.
A failed operation identifies its stage.
Communication failures make the Bridge offline; individual command rejection paths can recover through readback without taking the Bridge offline.

TRACE includes complete controller response payloads, including world responses, as well as room IDs and control values.
Pairing PINs and credentials must not be logged.
