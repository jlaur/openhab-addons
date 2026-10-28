# Danfoss Link Binding

This binding connects to a Danfoss Link CC controller through the MDG cloud relay.
It uses outbound connections on TCP port 443; no inbound port or LAN connection to the controller is required.

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

Pairing credentials are stored locally in openHAB.
These credentials survive restarts; protect your openHAB userdata and backups.
The pairing code is not persisted.
Removing a Thing does not revoke its app registration on the physical controller.

## Configuration

The controller Bridge supports an optional advanced `timeZone` setting.
Use a timezone ID such as `Europe/Copenhagen`, or leave it empty to use openHAB's timezone.

| Thing      | Parameter       | Required | Default          | Description                                |
| ---------- | --------------- | -------- | ---------------- | ------------------------------------------ |
| controller | refreshInterval | No       | 60               | Poll interval in seconds, minimum 10       |
| controller | timeZone        | No       | openHAB timezone | Controller timezone ID; advanced setting   |
| room       | roomId          | Yes      |                  | Non-negative room ID supplied by discovery |

The Bridge exposes the controller's serial number and firmware version as Thing properties.
The `systemTime` property contains the last controller-reported timestamp, including its UTC offset (for example, `2026-10-16T14:30:00+02:00`).
It updates when controller data is refreshed; it is not a continuously advancing clock and retains its last value during communication outages.
If the reported time differs from openHAB by more than five minutes, the binding logs a warning once after each Bridge initialization.
The binding does not set or correct the controller's clock.

## Channels

| Thing             | Channel              | Item Type          | Access     | Description                                                               |
| ----------------- | -------------------- | ------------------ | ---------- | ------------------------------------------------------------------------- |
| controller        | alarm                | Switch             | Read       | ON when unresolved alarms exist                                           |
| controller        | alarm-count          | Number             | Read       | Number of unresolved alarms                                               |
| controller        | alarms               | String             | Read       | Unresolved alarms as JSON                                                 |
| controller        | current-state        | String             | Read       | Controller-reported overall state, such as AtHome, Away or Asleep         |
| controller        | home                 | Switch             | Read/Write | ON applies the staged selection; OFF cancels Home                         |
| room, living-zone | schedule             | String             | Read/Write | Weekly heating schedule as JSON (advanced)                                |
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
Missing temperatures or unknown scales produce `UNDEF`, not zero.

Setpoint commands with units are converted to the controller's scale.
Unitless numeric commands use that scale directly.
Commands outside the room's reported minimum and maximum, commands with incompatible units, or commands without known room limits are rejected.
The setpoint channels expose the room-specific minimum and maximum temperatures.
The controller's own schedules and modes may subsequently change the current setpoint.
The three persistent setpoints are separate settings; writing one does not change the operating mode.
The controller may adjust related values, for example lowering Away/Sleeping when Home is lowered below it.

The manual switch follows the app's behaviour: for an independent room it changes only that room, but for a room in the Living zone it changes manual/scheduled operation for the entire Living zone.
An unknown or missing regulation type produces UNDEF; it is not reported as scheduled operation.
Manual commands are rejected when zone membership is unknown.

### Alarms

The Bridge reports unresolved controller alarms.
An empty list publishes OFF, 0 and `[]` to `alarm`, `alarm-count` and `alarms`, respectively.
Unavailable alarm data produces `UNDEF` for all three channels.
All unresolved alarms are included, including those already marked as read.
The `alarms` channel contains raw JSON with message identifiers rather than translated alarm descriptions.
These channels are read-only; the binding does not acknowledge or clear faults.

### Current State

The controller channel `current-state` reports the overall controller state.
Known values include `AtHome`, `Away`, `Asleep`, `Vacation`, `Pause`, `Manual`, `Boost`, `Normal`, `Off`, `On` and `Invalid`.
Unknown state names are preserved; missing or empty values and communication failures produce `UNDEF`.
The channel supports REFRESH and does not accept state-changing commands.

`current-state` and `home` report different information: the overall controller state and the explicit Home override respectively.
Scheduled Home can report `AtHome` while `home` is OFF; cancelling the override does not necessarily change `current-state` to `Away`.
For automation, `Away` or `Asleep` together with `home` OFF can be used as a condition for activating Home.
This overall state does not establish the current schedule period of each independently scheduled room.

### Home Override

Select rooms with their `home-selected` channels, then send ON to the controller's `home` channel.
Selection changes only stage intent, even while Home is active, and do not send controller requests.
Sending ON captures the current selection; subsequent selection edits do not change that queued request.
Send ON again to explicitly apply a revised selection while Home is active.
Send OFF to cancel Home, retaining the staged selection.

The Living zone and its member rooms share a single selection.
Changing any member's `home-selected` channel selects or deselects the whole zone and updates the other member channels.
Activation includes all current Living-zone rooms, including rooms without an openHAB Thing.
Standalone rooms are selected individually.
The binding rejects empty selections, missing selected rooms, changed standalone-room membership, and selected rooms whose regulation is not `Schedule`.
Resolve the selection or regulation setting and send ON again; the binding does not silently skip rooms or change manual mode.

The `home` switch reflects the controller-reported override: ON for Active, OFF for Inactive, or `UNDEF` when unavailable.
The `home-selected` channels represent staged intent, not the actual override membership.
Changes made in the app or on the controller update `home` without overwriting staged selections.
Selections start OFF, survive ordinary polls, reconnects and cancellation, and reset when the Bridge is reinitialized (including a binding restart).

### Controller Clock Conversion

Pause and Vacation DateTime channels use openHAB time.
For local controller timestamps, the binding uses the controller Bridge's optional advanced `timeZone` setting (for example, `Europe/Copenhagen`).
Leave it empty to use the timezone configured in openHAB regional settings.
Set it to the controller's timezone when that differs from openHAB; invalid timezone IDs put the Bridge OFFLINE with a configuration error.
The binding accounts for daylight-saving transitions and compensates for differences between the controller clock and openHAB time.
Small differences in reported dates can occur because of network latency and timestamp precision.
Clock compensation requires a valid controller timestamp and does not change the controller's clock.

### Pause

Send ON to `pause` to start a one-hour pause, or restart that hour if already active.
Send OFF to cancel Pause without cancelling other overrides.
A future DateTime command to `pause-until` starts Pause immediately or changes its deadline.
Dates are truncated to whole seconds; expired dates and dates beyond 2099 are rejected.
The binding always supplies an end time when activating Pause and cannot request indefinite Pause.

`pause` is ON while the controller reports Active and OFF for Inactive.
`pause-until` reports the controller's end timestamp, or `UNDEF` when inactive or no end is reported.
Missing or unrecognized Pause data produces `UNDEF` for both channels.

Timestamps use the configured timezone and the clock-skew conversion described above.

### Vacation

Vacation controls are on the controller Bridge and affect the whole system.
The `vacation-temperature` channel reads and changes the persistent Vacation temperature without activating or cancelling Vacation.
It uses the controller's temperature scale and exposes the reported minimum and maximum as dynamic channel limits.
Commands with units are converted to that scale; unitless commands use the controller's scale.
Commands outside those limits, with incompatible units, or without known limits or scale are rejected.
Missing values or an unknown temperature scale produce `UNDEF`.

The `vacation` switch is ON for both Planned and Active vacations, and OFF for Inactive.
Use `vacation-state` to distinguish those states.
Missing or unknown vacation data produces `UNDEF`.

While Inactive, commands to `vacation-start` and `vacation-end` stage dates locally without writing to the controller.
Routine Inactive refreshes preserve these staged dates.
Sending ON with neither date staged starts vacation immediately without an end time.
Sending ON with both dates staged plans the interval: both dates must be in the future, with end later than start.
Incomplete, expired, or reversed intervals are rejected and logged at DEBUG; they never fall back to indefinite vacation.
DateTime commands identify absolute times.
Dates use whole-second precision and the controller clock conversion described above.

While Planned, changing either date immediately submits the updated interval using the other date from a fresh controller response.
Each command must leave a valid future interval.
For example, to move a vacation beyond its current end, move the end first, then the start.
Rejected edits restore the controller-reported dates.
Date edits while Active are not supported; cancel and configure a new vacation instead.
Sending ON when already Planned or Active leaves the vacation unchanged.

Sending OFF cancels an active or planned vacation and clears locally staged dates.
To discard staged dates and start an indefinite vacation, send OFF, wait for confirmation, then send ON.
A vacation created or changed in the app or on the Link CC replaces staged values when polled.
When a reported vacation completes or is cancelled, the date channels are cleared to `UNDEF`.
Staged dates are held in memory and are cleared when the Bridge is restarted or removed.
The binding does not cancel Home or Pause or change room Manual/Schedule regulation when controlling Vacation.

### Living Zone

The `living-zone` Thing controls the shared Living zone.
Send ON to its `manual` channel for manual operation, or OFF to follow the zone's schedule.
Each room keeps its own temperature setpoints.
Configure room membership on the Danfoss Link CC controller.
Each room Thing exposes a `livingZone` property: `true` for a member of the Living zone, or `false` for an independent room.
The property is updated from controller responses and removed when the room's membership is missing or unrecognized.
During communication outages, it retains the last reported value.
The binding reads membership to determine which rooms share the Living zone's schedule and manual/scheduled mode; it does not change membership.

The zone's manual switch is ON when all members use manual operation and OFF when all members follow the schedule.
An empty zone, conflicting member modes, or missing/unknown member modes produce `UNDEF`.
The Thing remains available when the zone is empty.

The app's At Home override is a separate system-wide mode with its own selected-room list and timing.
It is not the Living zone's manual/automatic setting and is not exposed by this Thing.

### Schedules

The advanced `schedule` String channel on room and Living-zone Things reports the stored weekly heating schedule as JSON.
Link it to a String Item to enable fetching.
The binding reads it after normal heating updates, initially and then at the first controller poll after 15 minutes.
Sending `REFRESH` to the linked channel requests a fresh read, including when another cloud operation is already running.
After a failed read, the binding retries at the first normal poll after one minute.
Schedule failures publish `UNDEF` without discarding successfully read heating data or taking the Bridge offline solely because a schedule read failed.

Independent rooms read their own schedule.
Living-zone Things and member rooms report the shared Living-zone schedule.
If no member exists, or a room's membership is unknown, its schedule is `UNDEF`.

The schedule JSON uses lowercase weekday names and the following period fields:

```json
{"monday":[{"state":"Away","startTimeFromMidnight":"00:00:00","endTimeFromMidnight":"06:00:00"},{"state":"AtHome","startTimeFromMidnight":"06:00:00","endTimeFromMidnight":"22:00:00"},{"state":"Away","startTimeFromMidnight":"22:00:00","endTimeFromMidnight":"1.00:00:00"}]}
```

Times are recurring controller-local wall-clock times, without UTC conversion or clock-skew adjustment.
`1.00:00:00` means midnight at the end of the day.
Known states are `AtHome`, `Away` and `Asleep`.
Reported gaps, empty days and omitted days are preserved; the binding does not fill missing data when displaying it.
Missing schedules, unknown period states and malformed periods produce `UNDEF`.
Manual, Home, Pause and Vacation overrides do not erase the stored schedule; the schedule channel does not indicate which override is currently effective.

Send a JSON String command to `schedule` to replace the supplied weekdays.
Omitted weekdays remain unchanged; an empty day array requests all-day Away.
Within a supplied day, gaps are filled with Away and periods are sorted before sending.
Unknown fields or states, null days, incomplete periods, overlaps, reversed intervals and times with non-zero seconds or fractional seconds are rejected before any write.
Times must be whole minutes; use `1.00:00:00` for the end of the day.

For example, this command requests Home from 15:00 to 18:30 on Monday, with Away for the rest of that day:

```json
{"monday":[{"state":"AtHome","startTimeFromMidnight":"15:00:00","endTimeFromMidnight":"18:30:00"}]}
```

**Editing a Living-zone member room changes the schedule of the entire Living zone.**
Pending edits to the same target merge by weekday, including edits made through different member Things; the latest edit to each weekday wins.
Before sending, the binding checks fresh room membership; a queued edit is rejected if an originating room has changed membership or disappeared.
Schedule writes do not change Manual/Schedule regulation, Home, Pause or Vacation modes, or temperature setpoints.

The schedule channel displays the controller-confirmed schedule.
The last reported schedule remains visible while a refresh or update is in progress.
Failed reads or changes in room membership can produce `UNDEF`.
Linked schedule channels affected by the write are refreshed without waiting for the 15-minute interval.
If a write fails ambiguously, it is not automatically retried; the next successful refresh reports what the controller actually stored.

Schedules are edited using JSON commands; there is no graphical editor.

### Regulation State

The `regulation-state` channel publishes the controller's value unchanged.
Known values are listed below; other firmware versions may report additional values.

| Value             | Meaning                                                                              |
| ----------------- | ------------------------------------------------------------------------------------ |
| `Heating`         | Heating demand.                                                                      |
| `Deadband`        | No heating or cooling demand.                                                        |
| `Cooling`         | Cooling state. This does not establish that an installation supports active cooling. |
| `ForecastHeating` | Controller forecast-heating state; exact meaning is unknown.                         |
| `Invalid`         | Invalid state.                                                                       |

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

## Troubleshooting Commands

To trace a command, enable binding logging in the openHAB console:

```text
log:set TRACE org.openhab.binding.danfosslink
```

Send the command once and inspect `openhab.log`.
Communication failures make the Bridge offline until communication recovers.
Failed writes are not automatically replayed.

TRACE logs include controller response payloads, room IDs and control values; review them before sharing.
Restore the usual logging level after collecting the trace:

```text
log:set INFO org.openhab.binding.danfosslink
```
