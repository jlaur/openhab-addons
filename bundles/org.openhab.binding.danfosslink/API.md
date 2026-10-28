# Danfoss Link Controller API Inventory

This document inventories all 24 method/path combinations found in the supplied reverse-engineered Android app request builders, compared with the binding as of 2026-10-17.
It is not a guaranteed exhaustive specification of every controller firmware.
An app request builder establishes that the app knows an operation, not that every controller accepts it.
No additional controller requests were sent while preparing this inventory.

## Sources and transport

The primary reference is the supplied `danfoss-link.zip`, under `com.danfoss.linkapp/src/main/java/com/danfoss/cumulus/`.
Source names below refer to classes in its `comm/req/` directory unless otherwise stated.
This is reconstructed and partly modified source: demo behavior and commented-out code do not establish live controller behavior.
The saved `room_data.json`, previous live tests and the binding implementation provide additional evidence.

These are logical controller paths transported over the authenticated MDG cloud relay service `cc_json`.
They are not public HTTP URLs or evidence of a LAN HTTP listener.
The binding sends a `JsonWebRequest` envelope containing method, URI, headers and content over the controller TLS session.
Pairing and relay routing are separate MDG operations, not `/api` endpoints.
See [CloudClient.java](src/main/java/org/openhab/binding/danfosslink/internal/CloudClient.java), [Grid.java](src/main/java/org/openhab/binding/danfosslink/internal/Grid.java) and [WorldState.java](src/main/java/org/openhab/binding/danfosslink/internal/WorldState.java).

Support terminology:

- **Used**: the binding sends requests to this endpoint.
- **Via world**: selected corresponding fields are consumed from `GET /api/world`; the individual endpoint is not called.
- **Not implemented**: the binding does not expose the operation.
- **Rejected**: a write was attempted and rejected by the tested controller.

## Read endpoints

| Method | Path                           | Response/purpose                                                                              | Binding support                                                               | App source                                                        |
| ------ | ------------------------------ | --------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------- | ----------------------------------------------------------------- |
| GET    | `/api/world`                   | Aggregate rooms, system information/status, modes, settings, devices, alarms and API versions | Used; selected fields consumed                                                | `GetWorldRequest`, `GetWorldResponse`                             |
| GET    | `/api/rooms`                   | Room collection, temperatures, regulation, membership and state                               | Via world (`allRooms`)                                                        | `GetRoomsRequest`, `GetRoomsResponse`                             |
| GET    | `/api/rooms/{roomId}`          | Individual room; parser also accepts weekly `schedule` and `floorSetpoint`                    | Detailed room read and read/write schedule channel                            | `GetRoomRequest`, `GetRoomsResponse`                              |
| GET    | `/api/modes`                   | Override type, state, optional dates and room IDs                                             | Home, Pause and Vacation via world; Boost not exposed                         | `GetModesRequest`, `GetModesResponse`                             |
| GET    | `/api/system/status`           | `currentState`, daily `schedule`, `normalSchedule`, optional `outdoorTemperature`             | `currentState` via world; remaining fields not exposed                        | `GetSystemStatusRequest`, `GetSystemStatusResponse`               |
| GET    | `/api/system/info`             | Identity, version, clock, uptime, flags and optional diagnostics                              | Selected identity/clock fields via world                                      | `GetSystemInfoRequest`, `GetSystemInfoResponse`                   |
| GET    | `/api/system/apiversions`      | Supported API version numbers in `versions`                                                   | Not implemented; world can contain `supportedApiVersions`                     | `GetSystemApiVersionsRequest`, `GetSystemApiVersionsResponse`     |
| GET    | `/api/settings/temperature`    | Temperature scale and Pause/Vacation temperatures with limits                                 | Scale and Vacation setpoint/limits via world; Pause setpoint not exposed      | `GetTemperatureSettingsRequest`, `GetSettingsTemperatureResponse` |
| GET    | `/api/onoffdevices`            | Switchable device collection                                                                  | Not implemented; world can contain `onOffDevices`                             | `GetOnOffDevicesRequest`, `GetOnOffDevicesResponse`               |
| GET    | `/api/onoffdevices/{deviceId}` | Individual switchable device and schedule                                                     | Not implemented                                                               | `GetOnOffDeviceRequest`, `GetOnOffDevicesResponse`                |
| GET    | `/api/alarms`                  | Alarm collection                                                                              | API prepared; separate from world unresolved alarms, not polled automatically | `GetAlarmsRequest`, `GetAlarmsResponse`                           |

The base request defaults to required API version 1.
`GetAlarmsRequest`, `PutNotificationRegisterRequest` and `DeleteNotificationRequest` explicitly require version 3.
These are app compatibility gates, not proof that every other feature works on all version-1 controllers.
The supplied live snapshot lists versions 1, 2, 3 and 4.

## Write endpoints

| Method | Path                               | Payload/capability                                                  | Binding support                                                                                       | App source                              |
| ------ | ---------------------------------- | ------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------- | --------------------------------------- |
| PATCH  | `/api/rooms/{roomId}`              | Setpoints, regulation, schedule, ordering and zone membership       | Used for setpoints, standalone regulation and schedules; other fields unimplemented or rejected       | `PatchRoomRequest`                      |
| PATCH  | `/api/zones/living`                | Shared Living-zone regulation and schedule                          | Used for manual/scheduled regulation; schedule PATCH via schedule channel                             | `PatchRoomRequest`, `PatchZoneResponse` |
| PUT    | `/api/modes/athome`                | Activate Home for selected room IDs or cancel it                    | Used through staged selection and controller `home`                                                   | `PutModesRequest`                       |
| PUT    | `/api/modes/vacation`              | Immediate activation, dated period or cancellation                  | Used through Vacation switch and DateTime channels                                                    | `PutModesRequest`                       |
| PUT    | `/api/modes/pause`                 | Activation with optional end time or cancellation                   | Used for timed Pause; indefinite Pause deliberately unsupported                                       | `PutModesRequest`                       |
| PUT    | `/api/modes/boost`                 | Mode type `Boost`, activation state and optional dates              | Not implemented; behavior/applicability unverified                                                    | `PutModesRequest`                       |
| PATCH  | `/api/settings/temperature`        | `vacationTemperature.setpoint`                                      | Used through controller `vacation-temperature`                                                        | `PatchSettingsTemperatureRequest`       |
| PATCH  | `/api/system/info`                 | Controller `name`                                                   | Not implemented                                                                                       | `PatchSystemInfoRequest`                |
| POST   | `/api/system/updatesoftware`       | Request software update with `{}`                                   | Not implemented; update lifecycle unverified                                                          | `PostUpgradeSoftwareRequest`            |
| PATCH  | `/api/onoffdevices/{deviceId}`     | State, regulation, schedule and activity simulation                 | Not implemented                                                                                       | `PatchOnOffDeviceRequest`               |
| PATCH  | `/api/alarms/{alarmId}`            | Set `isRead`                                                        | Not implemented                                                                                       | `PatchAlarmRequest`                     |
| PUT    | `/api/notification/register`       | Register an FCM token for a pairing                                 | Not implemented                                                                                       | `PutNotificationRegisterRequest`        |
| DELETE | `/api/remote/pairings/{pairingId}` | Delete resource addressed by the app's notification-removal request | Not implemented; exact deletion scope unverified                                                      | `DeleteNotificationRequest`             |

## Room and Living-zone capabilities

Room patches contain `id` plus changed fields.
The following fields have explicit setters in `PatchRoomRequest`:

| Field                       | Purpose                       | Binding status                                                                      |
| --------------------------- | ----------------------------- | ----------------------------------------------------------------------------------- |
| `setpoint.temperature`      | Current setpoint              | Implemented                                                                         |
| `regulation.atHomeSetpoint` | Home temperature              | Implemented                                                                         |
| `regulation.awaySetpoint`   | Away/Sleeping temperature     | Implemented                                                                         |
| `regulation.manualSetpoint` | Manual temperature            | Implemented                                                                         |
| `regulation.type`           | `Manual` or `Schedule`        | Implemented for standalone rooms; member-room manual commands use the zone endpoint |
| `schedule.<weekday>`        | Scheduled periods             | Read/write schedule channel implemented                                             |
| `sortOrder`                 | Display ordering              | Not implemented                                                                     |
| `zone`                      | `Living` or `None` membership | Rejected; binding exposes read-only `livingZone` Thing property                     |

Membership writes still returned HTTP 400 when the existing `sortOrder` was included.
A setter in reconstructed app code is therefore not sufficient evidence that remote reassignment works.

The zone request starts with `name: Living` and uses top-level `regulationType`, unlike the room's nested field:

```json
{"name":"Living","regulationType":"Schedule"}
```

The same builder accepts schedules for the zone path.
No general zone enumeration, zone creation/deletion or separate `GET /api/zones/living` request was found.
The only explicit zone path in the request builders is `/api/zones/living`.

The room response parser also accepts `floorSetpoint.state` and `floorSetpoint.temperature`.
These optional fields are not exposed by the binding; no corresponding write setter was found.

### Schedule representation

Schedules are objects keyed by lowercase weekdays `monday` through `sunday`.
Each day contains periods with `state`, `startTimeFromMidnight` and `endTimeFromMidnight`.
For example:

```json
{
  "id": 2,
  "schedule": {
    "monday": [
      {"state":"Away","startTimeFromMidnight":"00:00:00","endTimeFromMidnight":"06:00:00"},
      {"state":"AtHome","startTimeFromMidnight":"06:00:00","endTimeFromMidnight":"22:00:00"},
      {"state":"Away","startTimeFromMidnight":"22:00:00","endTimeFromMidnight":"1.00:00:00"}
    ]
  }
}
```

This illustrates app encoding; binding schedule writes have not been live-tested.
`1.00:00:00` means midnight at the end of the day.
`comm/BaseRequest` sorts periods and fills gaps through minute 1440 with `Away` for heating schedules or `Off` for on/off schedules.
The controller also reports `Asleep` in system schedules; preserve that distinction when reading existing data.
Today's `systemStatus.schedule` and `normalSchedule` are not a complete weekly room schedule.
The binding exposes read/write weekly `schedule` channels on rooms and the Living zone.

### Binding schedule API

`CloudClient.Session.room(roomId)` reads `/api/rooms/{roomId}` into `RoomDetailsDTO`, including an optional `WeeklyScheduleDTO`.
It expects a room object with a matching `id`; detailed room schedule reads have been live-verified.
A missing schedule remains null, rather than being treated as an empty week.
There is no known zone GET endpoint; reading the shared schedule through a Living-zone member room has been live-verified.

`Session.roomSchedule(roomId, schedule)` and `Session.livingZoneSchedule(schedule)` send schedule-only PATCH requests.
They do not change manual/scheduled regulation or temperature setpoints.
The caller must choose the correct target from fresh room membership and read back the room after writing; these low-level methods do not manage handler queues or readback.
Linked schedule channels are read after world polling, normally every 15 minutes or on explicit REFRESH.
Reads for the same source room are deduplicated within each operation.
Living-zone consumers use the lowest-ID current member; shared reads have been live-verified.
Schedule String commands explicitly write supplied weekdays, merged by target while pending.
The handler revalidates captured membership before writing and invalidates affected linked schedule reads for readback.
HTTP rejections are read back; ambiguous transport failures are not replayed.
Partial-update semantics and weekly write acceptance still require live verification.

`WeeklyScheduleDTO` mirrors the seven lowercase weekday fields.
Each `SchedulePeriodDTO` contains a `ScheduleState` and two `LocalTime` fields.
Known states are `AtHome`, `Away`, and `Asleep`; unknown states deserialize to null and prevent writes.
The saved live system schedule contains `Asleep`; acceptance of that state in weekly writes is not yet verified.
The end-time adapter maps `1.00:00:00` to `LocalTime.MIDNIGHT` and serializes midnight ends back to `1.00:00:00`.
Midnight starts serialize as `00:00:00`; an interval from midnight to midnight means the entire day.
Recurring schedule times stay in the controller's local wall clock and are not shifted by the Pause/Vacation clock-skew conversion.

`HeatingSchedule` prepares a separate DTO for each write without modifying caller data.
It sorts periods and fills gaps with `Away`, matching the reviewed app builder.
Omitted days stay omitted; an explicitly empty day becomes all-day Away.
At least one day must be supplied.
Incomplete periods, unknown states, overlaps, reversed intervals and sub-minute boundaries are rejected before sending.
Cross-midnight periods must be split across days; no maximum number of periods is assumed.
The controller's limits and actual weekly read/write behavior still require live tests.

## Override modes

All four mode endpoints use `PutModesRequest`.
The path suffix is lowercase; body types are `AtHome`, `Vacation`, `Pause` and `Boost`.
The body includes `state`, optional `startTime`/`endTime`, and `rooms` only for active Home.
Absent dates are omitted, not emitted as nulls.

```json
{"type":"AtHome","state":"Active","rooms":[0,2]}
```

```json
{"type":"AtHome","state":"Inactive"}
```

The facade `comm/C0399i` activates Home without dates, immediate Vacation without dates, and dated Vacation with `Active` plus both boundaries.
The controller can then report dated Vacation as `Planned`.
Pause uses an end timestamp, not a minutes field; arbitrary finite deadlines have worked in live testing.
The generic Boost branch exists, but its supported systems, duration and behavior remain unverified.

`systemStatus.currentState` is separate from override activation in `modes`.
Scheduled `AtHome` can coexist with an `Inactive` Home override.
Room `state: Normal` does not identify that room's Home/Away schedule period.
See [README.md](README.md) for current channel and staging behavior.

Mode dates must use the controller clock and expected timestamp format.
The binding converts openHAB instants when populating DTOs, then serializes whole-second controller-local timestamps or the legacy UTC representation as appropriate.
Future timed operations must preserve this conversion.
Schedule times from midnight are a separate representation, not UTC instants.

## Unimplemented global settings and diagnostics

The explicit temperature-setting write is:

```json
{"vacationTemperature":{"setpoint":15.0}}
```

Reads provide `minSetpoint`, `maxSetpoint` and `setpoint` for both `vacationTemperature` and `pauseTemperature`; the world snapshot also supplies `temperatureScale`.
The reviewed setter only writes Vacation temperature: it does not establish write support for Pause temperature or the temperature scale.

The explicit system-info write is:

```json
{"name":"Home"}
```

Reading `systemTime`, `autoUpdateSoftware` or `limitPauseDuration` does not establish that they are writable.
No controller clock-setting request was found.
`POST /api/system/updatesoftware` with `{}` is separate from observing `autoUpdateSoftware`.

`GetSystemInfoResponse` also parses `uptime`, `cpuLoadPercentage`, `memoryLoadPercentage`, `pingCallTime` and `wifiSignalStrengthDb`.
These diagnostics and the status response's `outdoorTemperature` are potential future read-only features.
Availability and units need verification; missing fields must not be interpreted as real zero values.

## On/off devices

`GetOnOffDevicesResponse` accepts `id`, `name`, `state`, `regulation`, `activitySimulationEnabled` and a weekly `schedule`.
`model/OnOffDevice` defines state as `On`/`Off` and regulation as `Manual`/`Schedule`.
The patch builder always includes the ID and allows these fields independently:

```json
{"id":1,"state":"On"}
```

```json
{"id":1,"regulation":"Manual"}
```

```json
{"id":1,"activitySimulationEnabled":true}
```

Schedules use the weekday/period structure with `On` and `Off` states.
The binding has no device discovery, Things, channels or scheduling for these devices.
The supplied live snapshot has an empty device list, so suitable hardware is needed for validation.

## Alarms

`GetAlarmsResponse` parses `id`, `isRead`, `createdDate`, `level`, `room`, `node`, `descriptionTextId` and `titleTextId`.
Text IDs need a resource mapping to produce descriptions; they are not necessarily readable text.
The evidenced write changes the read flag:

```json
{"id":42,"isRead":true}
```

Marking an alarm read is not evidence of clearing the underlying fault.
The binding publishes `allUnresolvedAlarms` through the read-only Bridge channels `alarm`, `alarm-count` and `alarms`.
Missing or malformed collections produce unknown alarm states without failing heating polling.
`CloudClient.Session.alarms()` prepares the dedicated GET API; its scope and non-empty response shape still need live verification.
Alarm details retain the original JSON, including fields not yet understood.
Unverified date, severity, room/node and text-ID representations remain JSON fields in `AlarmDTO` until a non-empty payload is available.
There are no acknowledgement actions or localized message mappings yet.

## Notifications, pairing removal and events

Notification registration uses this shape:

```json
{"pairing":"<pairing-id>","platform":"fcm","token":"<notification-token>","level":"all"}
```

`model/CumulusModel.RegisterForNotifications` supplies the MDG device identifier as the pairing, with an alarm-notification preference defaulting to `all`.
This supports mobile notification registration, not evidence of a general room-state event subscription.
Calls to registration are commented out in some update paths of the supplied reconstructed source.

Despite its class name, `DeleteNotificationRequest` sends `DELETE /api/remote/pairings/{pairingId}` with `{}`.
Do not assume that this only unregisters FCM: its effect on the remote pairing itself is unverified.
Local credential persistence and PIN-based MDG pairing are separate from this endpoint.

`PutEventsResponse` exists, but no matching events URI or subscription payload was found in the reviewed request builders.
This inventory therefore does not assert an `/api/events` endpoint.
Event transport, subscription, reconnect and delivery behavior remain investigation items; the binding polls `/api/world`.

## Remaining implementation work

| Capability                      | Evidence                                               | Remaining work                                                                    |
| ------------------------------- | ------------------------------------------------------ | --------------------------------------------------------------------------------- |
| Room/Living-zone schedules      | PATCH builder and period encoding                      | Read/write schedule channels; graphical UI and live write tests remain            |
| Vacation temperature            | Implemented quantity channel, units and dynamic limits | Live controller validation                                                        |
| On/off devices                  | Read/write builders and model                          | Discovery, Things, channels, schedules and hardware validation                    |
| Alarms                          | World field, GET and read-flag PATCH                   | Live payload verification, text mapping and acknowledgement design                |
| Controller name                 | Explicit PATCH setter                                  | Configuration/action semantics and readback tests                                 |
| Diagnostics/outdoor temperature | Response parsers                                       | Availability, units and read-only channels                                        |
| Boost                           | Generic PUT branch                                     | Supported systems, duration and behavior                                          |
| Software updates                | POST builder                                           | Response, progress/reconnect behavior and user-triggered interface                |
| Notifications                   | API-v3 FCM registration                                | Suitability for openHAB and token lifecycle                                       |
| Pairing deletion                | API-v3 DELETE path                                     | Exact deletion scope and recovery                                                 |
| Room membership changes         | Setter but rejected live write                         | Establish controller restrictions before reconsidering support                    |
| General events                  | Response-class name only                               | Concrete subscription and transport evidence                                      |
