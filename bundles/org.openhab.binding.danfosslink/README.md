# Danfoss Link Binding

This binding connects to a Danfoss Link CC controller through the MDG cloud relay.
It uses outbound connections on TCP port 443; no inbound port or LAN connection to the controller is required.
The implementation is based on the reverse-engineered Android app protocol and has been developed against controller firmware 4.2.1724.

## Supported Things

- `controller`: Bridge representing a paired Danfoss Link CC.
- `room`: A room belonging to that Bridge.

Create a controller Bridge manually, then pair it.
Rooms are discovered after the first successful refresh and on subsequent refreshes.

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

| Thing | Parameter | Required | Default | Description |
| --- | --- | --- | --- | --- |
| controller | refreshInterval | No | 60 | Poll interval in seconds, minimum 10 |
| room | roomId | Yes | | Non-negative room ID supplied by discovery |

The Bridge exposes the controller's serial number and firmware version as Thing properties.

## Channels

| Thing | Channel | Item Type | Access | Description |
| --- | --- | --- | --- | --- |
| room | temperature | Number:Temperature | Read | Measured room temperature |
| room | setpoint | Number:Temperature | Read/Write | Requested room temperature |
| room | regulation-state | String | Read | Controller's regulation state |

Temperature states use the controller's reported temperature scale.
The observed protocol value is `Celcius`.
The binding also recognizes `Fahrenheit`, although this representation has not yet been verified against a controller configured to use Fahrenheit.
Missing temperatures or unknown scales produce `UNDEF`, not zero.

Setpoint commands with units are converted to the controller's scale.
Unitless numeric commands use that scale directly.
Commands outside the room's reported minimum and maximum, commands with incompatible units, or commands without known room limits are rejected.
The binding reads the controller again after a write to publish its reported state.
It does not automatically replay a failed write because the controller may already have applied it.
The controller's own schedules and modes may subsequently change the setpoint.

## Example

```java
Bridge danfosslink:controller:home "Danfoss Link" [ refreshInterval=60 ] {
    Thing room living "Living Room" [ roomId=0 ]
}
```

```java
Number:Temperature LivingTemperature "Temperature [%.1f %unit%]" { channel="danfosslink:room:home:living:temperature" }
Number:Temperature LivingSetpoint "Setpoint [%.1f %unit%]" { channel="danfosslink:room:home:living:setpoint" }
String LivingRegulation "Regulation [%s]" { channel="danfosslink:room:home:living:regulation-state" }
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
