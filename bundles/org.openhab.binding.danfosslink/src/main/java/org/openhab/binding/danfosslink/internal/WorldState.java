/*
 * Copyright (c) 2010-2026 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.danfosslink.internal;

import static org.openhab.binding.danfosslink.internal.DanfossLinkBindingConstants.*;
import static org.openhab.binding.danfosslink.internal.ProtocolJson.GSON;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import javax.measure.Unit;
import javax.measure.quantity.Temperature;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.danfosslink.internal.protocol.dto.ModeDTO;
import org.openhab.binding.danfosslink.internal.protocol.dto.ModeState;
import org.openhab.binding.danfosslink.internal.protocol.dto.ModeType;
import org.openhab.binding.danfosslink.internal.protocol.dto.RoomDTO;
import org.openhab.binding.danfosslink.internal.protocol.dto.SystemInfoDTO;
import org.openhab.binding.danfosslink.internal.protocol.dto.SystemStatusDTO;
import org.openhab.binding.danfosslink.internal.protocol.dto.TemperatureSettingDTO;
import org.openhab.binding.danfosslink.internal.protocol.dto.TemperatureSettingsDTO;
import org.openhab.binding.danfosslink.internal.protocol.dto.WorldDTO;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.unit.ImperialUnits;
import org.openhab.core.library.unit.SIUnits;
import org.openhab.core.types.Command;
import org.openhab.core.types.State;
import org.openhab.core.types.UnDefType;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * A consistent controller snapshot, including the unit required for temperatures and commands.
 *
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
public record WorldState(Map<Integer, Room> rooms, @Nullable Unit<Temperature> unit, @Nullable String brandingType,
        @Nullable String serialNumber, @Nullable String firmware, @Nullable ZonedDateTime systemTime,
        @Nullable Mode vacation, @Nullable Mode pause, @Nullable Mode home, @Nullable String currentState,
        @Nullable TemperatureSetting vacationTemperature) {

    public record TemperatureSetting(@Nullable BigDecimal setpoint, @Nullable BigDecimal minimum,
            @Nullable BigDecimal maximum) {
    }

    public record Mode(ModeState state, @Nullable Instant start, @Nullable Instant end, @Nullable ZoneId localZone) {
    }

    public record Room(int id, String name, @Nullable BigDecimal temperature, @Nullable BigDecimal setpoint,
            @Nullable BigDecimal minimum, @Nullable BigDecimal maximum, @Nullable String regulationState,
            @Nullable BigDecimal homeSetpoint, @Nullable BigDecimal awaySetpoint, @Nullable BigDecimal manualSetpoint,
            @Nullable String regulationType, @Nullable String zone) {
    }

    public static WorldState parse(JsonObject world) throws IOException {
        return parse(world, null);
    }

    public static WorldState parse(JsonObject world, @Nullable ZoneId zone) throws IOException {
        try {
            WorldDTO response = Objects.requireNonNull(GSON.fromJson(world, WorldDTO.class));
            Unit<Temperature> unit = null;
            var settings = response.temperatureSettings;
            String scale = settings == null ? null : settings.temperatureScale;
            if ("Celcius".equals(scale)) {
                unit = SIUnits.CELSIUS;
            } else if ("Fahrenheit".equals(scale)) {
                unit = ImperialUnits.FAHRENHEIT;
            }
            Map<Integer, Room> rooms = new LinkedHashMap<>();
            var allRooms = response.allRooms;
            if (allRooms == null) {
                throw new IOException("Missing room list");
            }
            for (RoomDTO room : allRooms) {
                var identifier = room.id;
                if (identifier == null) {
                    throw new IOException("Missing room ID");
                }
                int id = identifier.intValueExact();
                if (id < 0 || rooms.containsKey(id)) {
                    throw new IOException("Invalid or duplicate room ID");
                }
                String name = room.name;
                var setpoint = room.setpoint;
                var regulation = room.regulation;
                BigDecimal temperature = Boolean.FALSE.equals(room.hasTemperatureSensor) ? null
                        : room.ambientTemperature;
                rooms.put(id,
                        new Room(id, name == null ? "Room " + id : name, temperature,
                                setpoint == null ? null : setpoint.temperature, room.minSetpoint, room.maxSetpoint,
                                room.regulationState, regulation == null ? null : regulation.atHomeSetpoint,
                                regulation == null ? null : regulation.awaySetpoint,
                                regulation == null ? null : regulation.manualSetpoint,
                                regulation == null ? null : regulation.type, room.zone));
            }
            SystemInfoDTO info = response.systemInfo;
            SystemStatusDTO status = response.systemStatus;
            String currentState = status == null ? null : status.currentState;
            if (currentState != null) {
                // The app uses the first component when the controller reports a composite state.
                currentState = currentState.split(",", 2)[0].trim();
                if (currentState.isEmpty()) {
                    currentState = null;
                }
            }
            var vacationTemperature = settings == null ? null : settings.vacationTemperature;
            return new WorldState(Map.copyOf(rooms), unit, info == null ? null : info.brandingType,
                    info == null ? null : info.serialNo, info == null ? null : info.version,
                    info == null ? null : info.systemTime, mode(world, ModeType.VACATION, zone),
                    activeMode(world, ModeType.PAUSE, zone), activeMode(world, ModeType.AT_HOME, zone), currentState,
                    vacationTemperature == null ? null
                            : new TemperatureSetting(vacationTemperature.setpoint, vacationTemperature.minSetpoint,
                                    vacationTemperature.maxSetpoint));
        } catch (RuntimeException e) {
            throw new IOException("Malformed world response", e);
        }
    }

    private static @Nullable Mode activeMode(JsonObject world, ModeType type, @Nullable ZoneId zone) {
        Mode mode = mode(world, type, zone);
        return mode == null || mode.state() == ModeState.PLANNED ? null : mode;
    }

    private static @Nullable Mode mode(JsonObject world, ModeType type, @Nullable ZoneId zone) {
        // Optional mode data must not prevent room polling on older or unexpected controller responses.
        try {
            var modes = world.getAsJsonArray("modes");
            if (modes == null) {
                return null;
            }
            JsonObject info = world.getAsJsonObject("systemInfo");
            String systemTime = info == null ? null : text(info, "systemTime");
            // The app uses offset-free local requests when the controller reports a numeric time offset.
            ZoneOffset localOffset = systemTime == null || systemTime.endsWith("Z") ? null
                    : OffsetDateTime.parse(systemTime).getOffset();
            ZoneId localZone = localOffset == null ? null : zone == null ? localOffset : zone;
            Mode result = null;
            for (JsonElement element : modes) {
                JsonObject mode = element.getAsJsonObject();
                if (type == GSON.fromJson(mode.get("type"), ModeType.class)) {
                    ModeDTO response = Objects.requireNonNull(GSON.fromJson(mode, ModeDTO.class));
                    ModeState state = response.state;
                    if (result != null || state == null) {
                        return null;
                    }
                    // A present UTC field takes precedence even when explicitly null.
                    Instant start = localZone == null && mode.has("startTimeUtc") ? modeTime(response.startTimeUtc)
                            : modeTime(response.startTime, localZone);
                    Instant end = localZone == null && mode.has("endTimeUtc") ? modeTime(response.endTimeUtc)
                            : modeTime(response.endTime, localZone);
                    result = new Mode(state, start, end, localZone);
                }
            }
            return result;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static @Nullable Instant modeTime(@Nullable LocalDateTime value, @Nullable ZoneId zone) {
        // Out-of-range protocol dates represent unspecified interval boundaries.
        return value == null || value.getYear() < 1970 || value.getYear() > 2099 ? null
                : value.atZone(zone == null ? ZoneOffset.UTC : zone).toInstant();
    }

    private static @Nullable Instant modeTime(@Nullable Instant value) {
        return modeTime(value == null ? null : LocalDateTime.ofInstant(value, ZoneOffset.UTC), ZoneOffset.UTC);
    }

    public record Patch(String uri, JsonObject body) {
    }

    public Patch patch(int roomId, String channel, Command command) {
        Room room = rooms.get(roomId);
        if (room == null) {
            throw new IllegalArgumentException("Room unavailable");
        }
        JsonObject body = new JsonObject();
        body.addProperty("id", roomId);
        JsonObject values = new JsonObject();
        if (CHANNEL_MANUAL.equals(channel)) {
            if (!(command instanceof OnOffType onOff)) {
                throw new IllegalArgumentException("Manual mode requires ON or OFF");
            }
            String type = onOff == OnOffType.ON ? "Manual" : "Schedule";
            if ("Living".equals(room.zone())) {
                return livingZonePatch(channel, command);
            }
            if (!"None".equals(room.zone())) {
                throw new IllegalArgumentException("Room zone unavailable or unknown");
            }
            values.addProperty("type", type);
            body.add("regulation", values);
        } else {
            String field = switch (channel) {
                case CHANNEL_SETPOINT -> "temperature";
                case CHANNEL_HOME_SETPOINT -> "atHomeSetpoint";
                case CHANNEL_AWAY_SETPOINT -> "awaySetpoint";
                case CHANNEL_MANUAL_SETPOINT -> "manualSetpoint";
                default -> throw new IllegalArgumentException("Unsupported room channel");
            };
            values.addProperty(field, command(roomId, command));
            body.add(CHANNEL_SETPOINT.equals(channel) ? "setpoint" : "regulation", values);
        }
        return new Patch("/api/rooms/" + roomId, body);
    }

    public State livingZoneManualState() {
        String type = null;
        for (Room room : rooms.values()) {
            if ("Living".equals(room.zone())) {
                String memberType = room.regulationType();
                if ((!"Manual".equals(memberType) && !"Schedule".equals(memberType))
                        || (type != null && !type.equals(memberType))) {
                    return UnDefType.UNDEF;
                }
                type = memberType;
            }
        }
        return "Manual".equals(type) ? OnOffType.ON : "Schedule".equals(type) ? OnOffType.OFF : UnDefType.UNDEF;
    }

    public static Patch livingZonePatch(String channel, Command command) {
        if (!CHANNEL_MANUAL.equals(channel) || !(command instanceof OnOffType onOff)) {
            throw new IllegalArgumentException("Living zone supports manual mode ON or OFF");
        }
        JsonObject body = new JsonObject();
        body.addProperty("name", "Living");
        body.addProperty("regulationType", onOff == OnOffType.ON ? "Manual" : "Schedule");
        return new Patch("/api/zones/living", body);
    }

    BigDecimal command(int roomId, Command command) {
        Room room = rooms.get(roomId);
        return temperatureCommand(command, room == null ? null : room.minimum(), room == null ? null : room.maximum());
    }

    public Patch vacationTemperaturePatch(Command command) {
        TemperatureSetting setting = vacationTemperature;
        var temperature = new TemperatureSettingDTO();
        temperature.setpoint = temperatureCommand(command, setting == null ? null : setting.minimum(),
                setting == null ? null : setting.maximum());
        var body = new TemperatureSettingsDTO();
        body.vacationTemperature = temperature;
        return new Patch("/api/settings/temperature", GSON.toJsonTree(body).getAsJsonObject());
    }

    public State vacationTemperatureState() {
        TemperatureSetting setting = vacationTemperature;
        BigDecimal value = setting == null ? null : setting.setpoint();
        Unit<Temperature> scale = unit;
        return value == null || scale == null ? UnDefType.UNDEF : new QuantityType<>(value, scale);
    }

    private BigDecimal temperatureCommand(Command command, @Nullable BigDecimal minimum, @Nullable BigDecimal maximum) {
        Unit<Temperature> scale = unit;
        if (scale == null || minimum == null || maximum == null) {
            throw new IllegalArgumentException("Temperature scale or limits unavailable");
        }
        BigDecimal value;
        if (command instanceof QuantityType<?> quantity) {
            var converted = quantity.toUnit(scale);
            if (converted == null) {
                throw new IllegalArgumentException("Not a temperature quantity");
            }
            // Remove unit-conversion rounding noise before comparing controller limits.
            value = converted.toBigDecimal().setScale(6, RoundingMode.HALF_UP).stripTrailingZeros();
        } else if (command instanceof DecimalType decimal) {
            value = decimal.toBigDecimal();
        } else {
            throw new IllegalArgumentException("Unsupported setpoint command");
        }
        if (value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0) {
            throw new IllegalArgumentException("Setpoint outside controller limits");
        }
        return value;
    }

    private static @Nullable String text(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }
}
