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

import java.io.IOException;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.measure.Unit;
import javax.measure.quantity.Temperature;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
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
public record WorldState(Map<Integer, Room> rooms, @Nullable Unit<Temperature> unit, @Nullable String serialNumber,
        @Nullable String firmware) {
    public record Room(int id, String name, @Nullable BigDecimal temperature, @Nullable BigDecimal setpoint,
            @Nullable BigDecimal minimum, @Nullable BigDecimal maximum, @Nullable String regulationState,
            @Nullable BigDecimal homeSetpoint, @Nullable BigDecimal awaySetpoint, @Nullable BigDecimal manualSetpoint,
            @Nullable String regulationType, @Nullable String zone) {
    }

    public static WorldState parse(JsonObject world) throws IOException {
        try {
            Unit<Temperature> unit = null;
            JsonObject settings = world.getAsJsonObject("temperatureSettings");
            String scale = settings == null ? null : text(settings, "temperatureScale");
            if ("Celcius".equals(scale)) {
                unit = SIUnits.CELSIUS;
            } else if ("Fahrenheit".equals(scale)) {
                unit = ImperialUnits.FAHRENHEIT;
            }
            Map<Integer, Room> rooms = new LinkedHashMap<>();
            var allRooms = world.getAsJsonArray("allRooms");
            if (allRooms == null) {
                throw new IOException("Missing room list");
            }
            for (JsonElement element : allRooms) {
                JsonObject room = element.getAsJsonObject();
                int id = room.get("id").getAsBigDecimal().intValueExact();
                if (id < 0 || rooms.containsKey(id)) {
                    throw new IOException("Invalid or duplicate room ID");
                }
                String name = text(room, "name");
                JsonObject setpoint = room.getAsJsonObject("setpoint");
                JsonObject regulation = room.getAsJsonObject("regulation");
                BigDecimal temperature = decimal(room, "ambientTemperature");
                if (room.has("hasTemperatureSensor") && !room.get("hasTemperatureSensor").getAsBoolean()) {
                    temperature = null;
                }
                rooms.put(id, new Room(id, name == null ? "Room " + id : name, temperature,
                        setpoint == null ? null : decimal(setpoint, "temperature"), decimal(room, "minSetpoint"),
                        decimal(room, "maxSetpoint"), text(room, "regulationState"),
                        regulation == null ? null : decimal(regulation, "atHomeSetpoint"),
                        regulation == null ? null : decimal(regulation, "awaySetpoint"),
                        regulation == null ? null : decimal(regulation, "manualSetpoint"),
                        regulation == null ? null : text(regulation, "type"), text(room, "zone")));
            }
            JsonObject info = world.getAsJsonObject("systemInfo");
            return new WorldState(Map.copyOf(rooms), unit, info == null ? null : text(info, "serialNo"),
                    info == null ? null : text(info, "version"));
        } catch (RuntimeException e) {
            throw new IOException("Malformed world response", e);
        }
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
        Unit<Temperature> scale = unit;
        Room room = rooms.get(roomId);
        if (scale == null || room == null || room.minimum == null || room.maximum == null) {
            throw new IllegalArgumentException("Temperature scale or room limits unavailable");
        }
        BigDecimal minimum = java.util.Objects.requireNonNull(room.minimum);
        BigDecimal maximum = java.util.Objects.requireNonNull(room.maximum);
        BigDecimal value;
        if (command instanceof QuantityType<?> quantity) {
            var converted = quantity.toUnit(scale);
            if (converted == null) {
                throw new IllegalArgumentException("Not a temperature quantity");
            }
            // Remove unit-conversion rounding noise before comparing controller limits.
            value = converted.toBigDecimal().setScale(6, java.math.RoundingMode.HALF_UP).stripTrailingZeros();
        } else if (command instanceof DecimalType decimal) {
            value = decimal.toBigDecimal();
        } else {
            throw new IllegalArgumentException("Unsupported setpoint command");
        }
        if (value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0) {
            throw new IllegalArgumentException("Setpoint outside room limits");
        }
        return value;
    }

    private static @Nullable String text(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    private static @Nullable BigDecimal decimal(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? null : value.getAsBigDecimal();
    }
}
