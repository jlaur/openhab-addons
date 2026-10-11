# VELUX ACTIVE Binding

This binding supports VELUX ACTIVE with NETATMO accounts, NXG gateways, NXS indoor climate sensors, NXD departure switches and NXO windows/covers.
For KLF 200 gateways, use the [Velux binding](https://www.openhab.org/addons/bindings/velux/).

## Supported Things

| Thing | Thing type | Description |
| --- | --- | --- |
| Account | `account` | OAuth authentication and shared home status polling |
| Gateway | `gateway` | NXG gateway status and departure locking |
| Indoor Climate Sensor | `indoor-sensor` | NXS diagnostics and climate measurements from its assigned room |
| Departure Switch | `departure-switch` | NXD battery and connectivity diagnostics |
| Window/Cover | `opening` | NXO position, operating mode and unsigned movement commands |

Gateway Bridges are children of the Account Bridge.
Indoor sensors, departure switches and windows/covers are children of their Gateway Bridge and inherit its home ID.
An ONLINE account Bridge means authentication succeeded; gateway connectivity is represented by each Gateway Bridge.

## Discovery

Create and configure an Account Bridge, wait for it to become ONLINE, then start a VELUX ACTIVE discovery scan.
The account discovers NXG gateways, including gateways that are currently unreachable.
Accept the gateways from the Inbox, then scan again to discover their NXS sensors, NXD departure switches and NXO windows/covers.
Modules are associated with their gateway using `bridge`, falling back to `modules_bridged`.
Accept a discovered module from the Inbox to start polling.

Discovery reads topology on demand.
Status polling requests each configured home once per interval and distributes the result to its module Things.

## Account Configuration

| Parameter | Required | Default | Description |
| --- | --- | --- | --- |
| username | Yes | | Email address of the VELUX ACTIVE account |
| password | Yes | | Password of the VELUX ACTIVE account |
| clientId | No | VELUX application client ID | OAuth client ID override |
| clientSecret | No | VELUX application client secret | OAuth client secret override |
| refreshInterval | No | 60 | Home status polling interval in seconds; minimum 10 |

The binding uses core OAuth with a form-encoded password grant at `https://api.netatmo.com/oauth2/token`.
It requests scope `velux_scopes` and sends `user_prefix=velux` and `app_version=791302006`.
Core stores access and refresh tokens and handles refresh-token rotation.
Tokens survive Bridge disposal and openHAB restarts.
Removing the Bridge deletes its current OAuth credentials from the token store.
Changing account credentials while the binding is running invalidates its previous token session.

Token renewal is scheduled shortly before expiry.
A rejected refresh token triggers a new password grant.
Communication failures retry after 60 seconds without discarding stored tokens.
Rejected account or client credentials set the Bridge OFFLINE with a configuration error; correct the configuration to retry.
An API response rejecting the access token triggers token renewal; commands are not automatically replayed.

## Gateway Configuration

Discovery supplies the module identifier.
For manual configuration, obtain it from `homesdata` or `homestatus`.
The binding resolves the home automatically from account topology.
Existing `homeId` configuration is ignored and can be removed.
Topology is refreshed during shared polling; communication failures retain the last successful association.
An unresolved or ambiguous gateway remains UNKNOWN and cannot send commands until its home is resolved.

| Parameter | Required | Description |
| --- | --- | --- |
| moduleId | Yes | ID of the NXG gateway, usually formatted as a MAC address |

## Channels

The Account Bridge has no channels.
Gateway Bridges provide:

| Channel | Item type | Access | Description |
| --- | --- | --- | --- |
| locked | Switch | Read/write | Reported departure lock state; send ON to lock |
| locking | Switch | Read-only | ON while the gateway reports locking in progress |
| last-seen | DateTime | Read-only | Last contact timestamp reported by the service |

Departure locking sends the unsigned `away` scenario through the gateway.
This blocks movement through the VELUX system; it is not a door-lock command.
OFF is ignored with a warning: unlocking requires signing and is not implemented.
Automatic state prediction is disabled.
The binding updates `locked` to ON when the API acknowledges the lock command without errors.
This acknowledgement confirms command acceptance; the following status polls reconcile the channel with the gateway’s reported state.
After a successful command, the binding requests status immediately and then polls every five seconds for up to 30 seconds.

A gateway present in a successful home status response is considered reachable unless it reports `reachable=false` or an error for that gateway.
Error code 6 marks a gateway unreachable.
A gateway absent from a partial response has UNKNOWN status.
Unavailable or missing channel values are UNDEF, rather than interpreted as unlocked.
Channel refresh commands request a shared status update.

## Indoor Climate Sensor

The physical NXS sensor combines module diagnostics and measurements from its assigned room.
Discovery provides `moduleId`, which is the only required parameter for manual configuration under a Gateway Bridge.
The binding resolves the room from `room_id`, falling back to room membership when needed.
The module ID determines the Thing identity, so room reassignment preserves Item links.
The assigned room ID and name appear as Thing properties, along with the sensor firmware version.

| Channel | Item type | Description |
| --- | --- | --- |
| temperature | Number:Temperature | Room temperature |
| humidity | Number:Dimensionless | Relative humidity in percent |
| co2 | Number:Dimensionless | CO₂ concentration in ppm |
| illuminance | Number:Illuminance | Room illuminance in lux |
| battery-level | Number | Battery percentage |
| low-battery | Switch | ON for low or very low reported battery state |
| signal-strength | Number | Radio quality from 0 (very low) to 4 (full) |
| last-seen | DateTime | Last contact reported by the service |

All sensor channels are read-only.
Account topology is refreshed once per shared polling cycle to resolve gateway homes and sensor rooms.
A temporary topology failure retains the last known association and does not prevent module diagnostics from updating.
Missing room association or readings yield UNDEF climate channels.
An unreachable sensor yields UNDEF for all its channels.
If multiple sensors belong to one room, their climate channels show the same room-level readings; battery and signal values remain per-device.

## Departure Switch

The physical NXD departure switch is discovered automatically as a child of its Gateway Bridge.
Manual configuration under a Gateway Bridge requires `moduleId`; no room association is needed.
It exposes the read-only `battery-level`, `low-battery`, `signal-strength` and `last-seen` channels described above, and a firmware version property.
Reachability is reflected in Thing status.
Missing values become UNDEF; an unreachable switch clears all four channel states.
It uses the existing shared home status polling.

The available module status does not report button presses.
Departure lock state and locking commands remain on the Gateway Bridge's `locked` channel.

## Window/Cover

NXO modules are discovered as Window/Cover Things (`opening`), covering roof windows, blinds, venetian blinds, shutters and awning blinds.
Manual configuration under a Gateway Bridge requires `moduleId`.
The device subtype is learned from home status; commands use the parent gateway.
Reported properties include `veluxType`, `gatewayId`, `firmwareVersion` and `vendor`.

| Channel | Item type | Access | Description |
| --- | --- | --- | --- |
| position | Rollershutter | Read/write | Current position: 0% open, 100% closed |
| mode | String | Read-only | Reported operating mode, for example `algo_available` or `algo_disabled` |
| last-seen | DateTime | Read-only | Last contact timestamp reported by the service |

The binding normalizes the different API position conventions for windows and covers.
UP requests fully open, DOWN requests fully closed, and percentage commands set intermediate positions.
Blinds and shutters support unsigned position commands.
Roof windows support closing (DOWN or 100%); opening or partial positioning is ignored with a warning until signing is implemented.

**STOP stops all movements on the associated gateway**, including other windows and covers.
It is the gateway-wide stop operation used by the VELUX app, not an individual actuator stop.
The binding does not unlock departure mode or force movement past rain restrictions.

The position channel shows the requested destination immediately after the API accepts a position command.
Subsequent status polls reconcile it with the actual position reported by the device; acceptance does not mean movement is complete.
Failed commands and STOP do not publish an expected position.
Automatic state prediction is disabled.
Successful commands trigger an immediate shared poll and five-second polling for up to 30 seconds.
Missing or out-of-range positions become UNDEF.
An unknown subtype also produces UNDEF positions and prevents position commands rather than guessing the direction.
Unreachable modules clear their channels; modules absent from a response have UNKNOWN status.

## Gateway Properties

Reported versions are exposed as `firmwareVersion` (Netatmo) and `hardwareVersion`.
Missing version fields in a partial response preserve the previously reported properties.

## Example

```java
Bridge veluxactive:account:home "VELUX ACTIVE Account" [
    username="YOUR_ACCOUNT_EMAIL",
    password="YOUR_ACCOUNT_PASSWORD",
    refreshInterval=60
] {
    Bridge gateway main "VELUX Gateway" [
        moduleId="YOUR_GATEWAY_ID"
    ] {
        Thing indoor-sensor climate [ moduleId="YOUR_SENSOR_ID" ]
        Thing departure-switch departure [ moduleId="YOUR_SWITCH_ID" ]
        Thing opening window [ moduleId="YOUR_OPENING_ID" ]
    }
}
```

```java
Rollershutter VeluxWindow { channel="veluxactive:opening:home:main:window:position" }
Switch VeluxDepartureLocked "Departure Locked" { channel="veluxactive:gateway:home:main:locked", autoupdate="false" }
Switch VeluxDepartureLocking "Locking" { channel="veluxactive:gateway:home:main:locking" }
DateTime VeluxGatewayLastSeen "Last Seen" { channel="veluxactive:gateway:home:main:last-seen" }
```

Send ON to `VeluxDepartureLocked` to request departure locking.
Use `VeluxDepartureLocked` as the reported state in rules.

## Remaining Capabilities

Local signing-key pairing, signed window-opening commands and departure unlocking are a later milestone.
All four module types (NXG, NXS, NXD and NXO) have Thing support.

## Protocol References

- [VELUX ACTIVE integration](https://github.com/Niek/ha-velux-active)
- [Authentication and status implementation](https://github.com/Niek/ha-velux-active/blob/main/custom_components/velux_active/api.py)
- [Departure control implementation](https://github.com/Niek/ha-velux-active/blob/main/custom_components/velux_active/lock.py)
- [Gateway connectivity interpretation](https://github.com/Niek/ha-velux-active/blob/main/custom_components/velux_active/connectivity.py)
