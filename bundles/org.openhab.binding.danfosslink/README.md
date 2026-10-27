# Danfoss Link Binding

This binding connects to a Danfoss Link CC controller through the MDG cloud relay.
It uses outbound connections on TCP port 443; no inbound port or LAN connection to the controller is required.
The implementation is based on the reverse-engineered Android app protocol and has been developed against controller firmware 4.2.1724.

## Supported Things

- `controller`: Bridge representing a paired Danfoss Link CC.
- `room`: A room belonging to that Bridge.
- `living-zone`: The controller's built-in Living zone, also a child of the Bridge.

Create a controller Bridge manually, then pair it.
Rooms and the Living zone are discovered after the first successful refresh and on subsequent refreshes.
The Living-zone Thing needs no configuration parameters.

## Pairing

On the controller, start the procedure for connecting a new app and obtain its nine-digit pairing code.
Invoke the controller's `pair` Thing action while that code is valid.
Keep the code as a string to preserve leading zeroes.

For example, run this Rules DSL script once, substituting your Bridge UID and current pairing code:

```java
val actions = getActions("danfosslink", "danfosslink:controller:home")
val result = actions.pair("012345678")
logInfo("danfosslink", "{}", result)
```

The action returns immediately; the Bridge status reports the outcome.
If the Bridge is busy, wait for the current operation to finish and invoke the action again.
Successful pairing is followed by a refresh.
If pairing fails, obtain a fresh code and retry.
An offline controller can be paired again if its previous app registration was removed.

The binding generates its own app identity and persists its private key and paired controller certificate using openHAB's StorageService, keyed by Bridge UID.
These credentials survive restarts; protect your openHAB userdata and backups.
The pairing code is not persisted.
Removing a Thing does not revoke its app registration on the physical controller.

## Configuration

The controller Bridge supports an optional advanced `timeZone` setting.
Use a timezone ID such as `Europe/Copenhagen`, or leave it empty to use openHAB's timezone.

| Thing | Parameter | Required | Default | Description |
| --- | --- | --- | --- | --- |
| controller | refreshInterval | No | 60 | Poll interval in seconds, minimum 10 |
| controller | timeZone | No | openHAB timezone | Controller timezone ID; advanced setting |
| room | roomId | Yes | | Non-negative room ID supplied by discovery |

The Bridge exposes the controller's serial number and firmware version as Thing properties.
The `systemTime` property contains the last controller-reported timestamp, including its UTC offset (for example, `2026-10-16T14:30:00+02:00`).
It updates when controller data is refreshed; it is not a continuously advancing clock and retains its last value during communication outages.
If the reported time differs from openHAB by more than five minutes, the binding logs a warning once per controller handler lifecycle.
The binding does not set or correct the controller's clock.

## Channels

| Thing             | Channel              | Item Type          | Access     | Description                                                               |
| ---               | ---                  | ---                | ---        | ---                                                                       |
| controller        | current-state        | String             | Read       | Controller-reported overall state, such as AtHome, Away or Asleep         |
| controller        | home                 | Switch             | Read/Write | ON applies the staged selection; OFF cancels Home                         |
| room, living-zone | home-selected        | Switch             | Read/Write | Stage inclusion in the next Home activation                               |
| controller        | pause                | Switch             | Read/Write | ON starts or restarts a one-hour pause; OFF cancels Pause                 |
| controller        | pause-until          | DateTime           | Read/Write | Start or adjust Pause until a future deadline                             |
| controller        | vacation             | Switch             | Read/Write | ON enables vacation; OFF cancels vacation and clears staged dates         |
| controller        | vacation-temperature | Number:Temperature | Read/Write | Persistent temperature used during Vacation                               |
| controller        | vacation-state       | String             | Read       | Inactive, Planned, or Active                                              |
| controller        | vacation-start       | DateTime           | Read/Write | Staged or controller-reported vacation start                              |
| controller        | vacation-end         | DateTime           | Read/Write | Staged or controller-reported vacation end                                |
| room              | temperature          | Number:Temperature | Read       | Measured room temperature                                                 |
| room              | setpoint             | Number:Temperature | Read/Write | Current target temperature; temporary override during scheduled operation |
| room              | home-setpoint        | Number:Temperature | Read/Write | Persistent temperature for Home periods                                   |
| room              | away-setpoint        | Number:Temperature | Read/Write | Persistent temperature for Away/Sleeping periods                          |
| room              | manual-setpoint      | Number:Temperature | Read/Write | Persistent temperature for manual operation                               |
| room              | manual               | Switch             | Read/Write | ON selects manual operation; OFF resumes the schedule                     |
| room              | regulation-state     | String             | Read       | Controller's regulation state                                             |
| living-zone       | manual               | Switch             | Read/Write | ON selects manual operation for the Living zone; OFF resumes its schedule |

Temperature states use the controller's reported temperature scale.
The observed protocol value is `Celcius`.
The binding also recognizes `Fahrenheit`, although this representation has not yet been verified against a controller configured to use Fahrenheit.
Missing temperatures or unknown scales produce `UNDEF`, not zero.

Setpoint commands with units are converted to the controller's scale.
Unitless numeric commands use that scale directly.
Commands outside the room's reported minimum and maximum, commands with incompatible units, or commands without known room limits are rejected.
The four setpoint channels also expose these room-specific limits as dynamic state-description minimum and maximum values.
Their display pattern uses the controller's temperature unit so the bounds and displayed values use the same scale.
The bounds are refreshed when the controller reports changes; no step size is inferred from them.
The binding reads the controller again after a write to publish its reported state.
It does not automatically replay a failed write because the controller may already have applied it.
The controller's own schedules and modes may subsequently change the current setpoint.
The three persistent setpoints are separate settings; writing one does not change the operating mode.
The controller may adjust related values, for example lowering Away/Sleeping when Home is lowered below it.
Reported values are refreshed after writes.

The manual switch follows the app's behaviour: for an independent room it changes only that room, but for a room in the Living zone it changes manual/scheduled operation for the entire Living zone.
The binding refreshes all rooms afterwards.
An unknown or missing regulation type produces UNDEF; it is not reported as scheduled operation.
Manual commands are rejected when zone membership is unknown.

### Current State

The controller channel `current-state` reports `systemStatus.currentState` from the controller, without evaluating schedules locally.
Values recognized by the app include `AtHome`, `Away`, `Asleep`, `Vacation`, `Pause`, `Manual`, `Boost`, `Normal`, `Off`, `On` and `Invalid`.
If the controller reports a comma-separated composite state, the binding uses its first component, matching the app.
Unknown state names are preserved; missing or empty values and communication failures produce `UNDEF`.
The channel supports REFRESH and does not accept state-changing commands.

`current-state` and `home` report different information: the overall controller state and the explicit Home override respectively.
Scheduled Home can report `AtHome` while `home` is OFF; cancelling the override does not necessarily change `current-state` to `Away`.
Both channels are updated from controller readback, without deriving one from the other.
For automation, `Away` or `Asleep` together with `home` OFF can be used as a condition for activating Home.
This overall state does not establish the current schedule period of each independently scheduled room.

### Home Override

Select rooms with their `home-selected` channels, then send ON to the controller's `home` channel.
Room identifiers are resolved by the binding; users do not supply them.
Selection changes only stage intent, even while Home is active, and do not send controller requests.
Sending ON captures the current selection; subsequent selection edits do not change that queued request.
Send ON again to explicitly apply a revised selection while Home is active.
This repeated-activation behavior still needs verification on the controller.
Send OFF to cancel Home through its own mode endpoint, retaining the staged selection.

The Living zone and its member rooms share a single selection.
Changing any member's `home-selected` channel selects or deselects the whole zone and updates the other member channels.
Activation includes all current Living-zone rooms, including rooms without an openHAB Thing.
Standalone rooms are selected individually.
The binding rejects empty selections, missing selected rooms, changed standalone-room membership, and selected rooms whose regulation is not `Schedule`.
Resolve the selection or regulation setting and send ON again; the binding does not silently skip rooms or change manual mode.

The `home` switch reflects the controller-reported override: ON for Active, OFF for Inactive, or `UNDEF` when unavailable.
The `home-selected` channels represent staged intent, not the actual override membership.
Changes made in the app or on the controller update `home` without overwriting staged selections.
Selections start OFF, survive ordinary polls, reconnects and cancellation, and reset when the controller handler is reinitialized (including a binding restart).
Failed writes are not replayed automatically; controller rejections are logged at DEBUG and followed by readback.
TRACE logging includes the Home request for troubleshooting.

### Controller Clock Conversion

Pause and Vacation DateTime channels use openHAB time.
For local controller timestamps, the binding uses the controller Bridge's optional advanced `timeZone` setting (for example, `Europe/Copenhagen`).
Leave it empty to use the timezone configured in openHAB regional settings.
Set it to the controller's timezone when that differs from openHAB; invalid timezone IDs put the Bridge OFFLINE with a configuration error.
The offset is calculated separately for each date, so planned periods can cross daylight-saving transitions.
For each world response, the binding estimates clock skew by comparing the controller's `systemTime` with openHAB time when the response is received.
Outgoing deadlines are shifted into controller time; incoming mode dates are shifted back into openHAB time before validation or publication.
This also applies to vacations configured outside openHAB and to edits of planned vacation dates.
Staged vacation dates stay in openHAB time until sent using a fresh controller response.

The estimate is refreshed for every response and is not retained across connections.
Network latency and the controller's timestamp precision can cause small differences in readback.
If a response has no usable `systemTime`, dates remain unadjusted for that response.
The `systemTime` property and clock-skew warning remain uncorrected; the binding does not change the controller clock.

### Pause

Send ON to `pause` to start a one-hour pause, or restart that hour if already active.
Send OFF to cancel Pause without cancelling other overrides.
A future DateTime command to `pause-until` starts Pause immediately or changes its deadline.
Dates are truncated to whole seconds; expired dates and dates beyond 2099 are rejected.
The binding always supplies an end time when activating Pause and cannot request indefinite Pause.

The channels publish controller readback rather than assuming commands succeeded.
`pause` is ON while the controller reports Active and OFF for Inactive.
`pause-until` reports the controller's end timestamp, or `UNDEF` when inactive or no end is reported.
Missing or unrecognized Pause data produces `UNDEF` for both channels.
Commands rejected by the controller are logged at DEBUG and followed by a refresh; failed writes are not retried automatically.
The controller manages expiration, so openHAB does not schedule a separate cancellation.

Arbitrary durations still need verification on a real controller.
To test, send a deadline five minutes in the future, check the returned `pause-until`, and confirm that `pause` returns to OFF after expiration (allowing for the polling interval).
A successful request alone does not prove that the controller honors the requested duration.
Timestamps use the configured timezone and the clock-skew conversion described above.
TRACE logging includes the outgoing Pause request for comparison with readback.

### Vacation

Vacation controls are on the controller Bridge and affect the whole system.
The `vacation-temperature` channel reads and changes the persistent Vacation temperature without activating or cancelling Vacation.
It uses the controller's temperature scale and exposes the reported minimum and maximum as dynamic channel limits.
Commands with units are converted to that scale; unitless commands use the controller's scale.
Commands outside those limits, with incompatible units, or without known limits or scale are rejected.
The setting is read back after a write; missing values or an unknown scale produce `UNDEF`.

The `vacation` switch is ON for both Planned and Active vacations, and OFF for Inactive.
Use `vacation-state` to distinguish those states.
Missing or unknown vacation data produces `UNDEF`.

While Inactive, commands to `vacation-start` and `vacation-end` stage dates locally without writing to the controller.
Routine Inactive refreshes preserve these staged dates.
Sending ON with neither date staged starts vacation immediately without an end time.
Sending ON with both dates staged plans the interval: both dates must be in the future, with end later than start.
Incomplete, expired, or reversed intervals are rejected and logged at DEBUG; they never fall back to indefinite vacation.
DateTime commands identify absolute times.
Requests follow the controller's UTC or local-time convention using the offset in `systemInfo.systemTime`, with whole-second precision.

While Planned, changing either date immediately submits the updated interval using the other date from a fresh controller response.
Each command must leave a valid future interval.
For example, to move a vacation beyond its current end, move the end first, then the start.
Accepted dates are refreshed from the controller, including any normalization.
Rejected edits restore the controller-reported dates.
Date edits while Active are not supported; cancel and configure a new vacation instead.
Sending ON when already Planned or Active leaves the vacation unchanged.

Sending OFF cancels an active or planned vacation and clears locally staged dates.
To discard staged dates and start an indefinite vacation, send OFF, wait for confirmation, then send ON.
A vacation created or changed in the app or on the Link CC replaces staged values when polled.
When a reported vacation completes or is cancelled, the date channels are cleared to `UNDEF`.
Staged dates are held in memory and are cleared when the Bridge handler is restarted or disposed.
The binding does not cancel Home or Pause or change room Manual/Schedule regulation when controlling Vacation.

### Living Zone

The `living-zone` Thing controls the shared Living zone without selecting a representative room.
Its `manual` switch uses the same zone command as the manual switch of a member room.
ON selects manual operation; OFF selects automatic operation following the zone's schedule.
Each room keeps its own temperature setpoints.
Configure room membership on the Danfoss Link CC controller.
Each room Thing exposes a `livingZone` property: `true` for a member of the Living zone, or `false` for an independent room.
The property is updated from controller responses and removed when the room's membership is missing or unrecognized.
During communication outages, it retains the last reported value.
The binding reads membership to determine which rooms share the Living zone's schedule and manual/scheduled mode; it does not change membership.

The controller's world response reports regulation on the member rooms rather than a separate zone object.
The zone's manual state is therefore derived from its members: all must report Manual for ON or Schedule for OFF.
An empty zone, conflicting member modes, or missing/unknown member modes produce `UNDEF`.
The Thing remains available when the zone is empty.

The app's At Home override is a separate system-wide mode with its own selected-room list and timing.
It is not the Living zone's manual/automatic setting and is not exposed by this Thing.
Schedule editing is not yet implemented.

### Regulation State

The `regulation-state` channel publishes the controller's value unchanged.
The Android app defines the following values; other firmware versions may report additional values.

| Value | Meaning |
| --- | --- |
| `Heating` | Heating demand. The app displays its heating indicator only for this value. |
| `Deadband` | Idle/no heating or cooling demand. Also observed during Pause. |
| `Cooling` | Cooling state. This does not establish that an installation supports active cooling. |
| `ForecastHeating` | Apparently predictive/preheating operation; its precise meaning has not been verified. |
| `Invalid` | The app's fallback for missing or unrecognized values; not necessarily a value sent by the controller. |

The binding publishes `UNDEF` when the value is missing or the room/controller is unavailable.
It does not replace unrecognized values with `Invalid`.

## Example

```java
Bridge danfosslink:controller:home "Danfoss Link" [ refreshInterval=60 ] {
    Thing room living "Living Room" [ roomId=0 ]
    Thing living-zone living "Living Zone"
}
```

```java
Number:Temperature LivingTemperature "Temperature [%.1f %unit%]" { channel="danfosslink:room:home:living:temperature" }
Number:Temperature LivingSetpoint "Setpoint [%.1f %unit%]" { channel="danfosslink:room:home:living:setpoint" }
String LivingRegulation "Regulation [%s]" { channel="danfosslink:room:home:living:regulation-state" }
Switch LivingZoneManual "Living Zone Manual" { channel="danfosslink:living-zone:home:living:manual" }
```

## Transport and Certificates

The binding includes the public MDG CA certificate extracted from the Android app.
No APK, external certificate file, Python installation, or Java security configuration changes are required.
Cloud certificates are validated against this trust anchor and the MDG control service identity.
Controller certificates are authenticated during PACE pairing and pinned for subsequent connections.

Bouncy Castle is included for the legacy TLS and PACE protocols.
The controller uses an ECDSA certificate with explicit Brainpool P320r1 parameters, and the original TLS implementation uses a legacy supported-group identifier.
Enabling `TLS_RSA_*` suites does not resolve these EC certificate and protocol compatibility issues.
The binding keeps this compatibility handling local rather than changing global JVM security settings.

Cloud service availability and future protocol compatibility are outside the binding's control.
Communication failures mark the Bridge offline; polling retries automatically.

## Troubleshooting Commands

To trace a command, enable binding logging in the openHAB console:

```text
log:set TRACE org.openhab.binding.danfosslink
```

Send the command once and inspect `openhab.log`.
The log records commands being applied, request URIs, write bodies, response HTTP status, and bounded controller error details.
A failed operation identifies its stage; HTTP rejections also appear in the controller Bridge status.
A rejection or communication failure makes the Bridge offline and room channel states undefined until communication recovers.
Failed writes are not automatically replayed.

The trace includes room IDs and control values, but does not log pairing PINs, credentials, or complete world responses.
Restore the usual logging level after collecting the trace:

```text
log:set INFO org.openhab.binding.danfosslink
```
