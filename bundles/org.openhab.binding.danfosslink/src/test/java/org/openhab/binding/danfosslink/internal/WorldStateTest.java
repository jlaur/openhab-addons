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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.unit.SIUnits;
import org.openhab.core.storage.Storage;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Unit conversion, input validation, persistence, and cancellation boundaries.
 *
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
class WorldStateTest {
    private interface StringStorage extends Storage<String> {
    }

    private static JsonObject world() {
        return JsonParser.parseString("""
                {"temperatureSettings":{"temperatureScale":"Celcius"},
                 "systemInfo":{"serialNo":"123","version":"4.2"},
                 "allRooms":[{"id":0,"name":"Room","ambientTemperature":20.5,
                 "setpoint":{"temperature":21},"minSetpoint":6,"maxSetpoint":28,
                 "regulationState":"Heating"}]}
                """).getAsJsonObject();
    }

    @Test
    void temperaturesAndCommandsRespectScaleAndLimits() throws Exception {
        var state = WorldState.parse(world());
        assertEquals(SIUnits.CELSIUS, state.unit());
        assertEquals(0, state.command(0, new QuantityType<>("82.4 °F")).compareTo(new BigDecimal("28")));
        assertEquals(0, state.command(0, new QuantityType<>("42.8 °F")).compareTo(new BigDecimal("6")));
        assertEquals("123", state.serialNumber());
        assertEquals("4.2", state.firmware());
        assertEquals(new BigDecimal("20.5"), java.util.Objects.requireNonNull(state.rooms().get(0)).temperature());
        assertEquals(0, state.command(0, new QuantityType<>("68 °F")).compareTo(new BigDecimal("20")));
        assertEquals(0, state.command(0, new DecimalType(6)).compareTo(new BigDecimal("6")));
        assertEquals(0, state.command(0, new DecimalType(28)).compareTo(new BigDecimal("28")));
        assertThrows(IllegalArgumentException.class, () -> state.command(0, new DecimalType(29)));
        assertThrows(IllegalArgumentException.class, () -> state.command(1, new DecimalType(20)));
        assertThrows(IllegalArgumentException.class, () -> state.command(0, new QuantityType<>("20 W")));
        var json = world();
        json.getAsJsonObject("temperatureSettings").addProperty("temperatureScale", "Fahrenheit");
        var room = json.getAsJsonArray("allRooms").get(0).getAsJsonObject();
        room.addProperty("minSetpoint", 40);
        room.addProperty("maxSetpoint", 85);
        assertEquals(0, WorldState.parse(json).command(0, new QuantityType<>("20 °C")).compareTo(new BigDecimal("68")));
    }

    @Test
    void missingAndUnknownValuesAreNotInvented() throws Exception {
        var json = world();
        json.getAsJsonObject("temperatureSettings").addProperty("temperatureScale", "Unknown");
        var room = json.getAsJsonArray("allRooms").get(0).getAsJsonObject();
        room.remove("ambientTemperature");
        room.remove("setpoint");
        var state = WorldState.parse(json);
        assertNull(state.unit());
        assertNull(java.util.Objects.requireNonNull(state.rooms().get(0)).temperature());
        assertNull(java.util.Objects.requireNonNull(state.rooms().get(0)).setpoint());
        assertThrows(IllegalArgumentException.class, () -> state.command(0, new DecimalType(20)));
        json.getAsJsonArray("allRooms").add(room.deepCopy());
        assertThrows(IOException.class, () -> WorldState.parse(json));
        assertThrows(IOException.class, () -> WorldState.parse(new JsonObject()));
    }

    @Test
    void pairingSurvivesNewStoreInstance() throws Exception {
        Storage<String> storage = mock(StringStorage.class);
        Map<String, String> values = new HashMap<>();
        when(storage.get(anyString())).thenAnswer(call -> values.get(call.getArgument(0)));
        when(storage.put(anyString(), anyString()))
                .thenAnswer(call -> values.put(call.getArgument(0), call.getArgument(1)));
        var first = new PairingStore(storage).load("controller");
        var controller = Identity.generate().certificate;
        new PairingStore(storage).save("controller", new PairingStore.Credentials(first.identity, controller));
        var loaded = new PairingStore(storage).load("controller");
        assertEquals(first.identity.encode(), loaded.identity.encode());
        assertEquals(controller, loaded.controller);
        values.put("broken", "{}");
        assertThrows(IOException.class, () -> new PairingStore(storage).load("broken"));
    }

    @Test
    void cancellationClosesExistingAndFutureSockets() throws Exception {
        var operation = new Operation();
        var socket = new Socket();
        operation.add(socket);
        operation.close();
        assertTrue(socket.isClosed());
        assertThrows(IOException.class, operation::check);
        var later = new Socket();
        assertThrows(IOException.class, () -> operation.add(later));
        assertTrue(later.isClosed());
        operation.close();
    }

    @Test
    void persistentSetpointsAreParsedAndPatchedIndependently() throws Exception {
        var json = world();
        var room = json.getAsJsonArray("allRooms").get(0).getAsJsonObject();
        room.add("regulation", JsonParser.parseString("""
                {"type":"Schedule","atHomeSetpoint":22,"awaySetpoint":17,"manualSetpoint":20}
                """));
        room.addProperty("zone", "Living");
        var state = WorldState.parse(json);
        var parsed = java.util.Objects.requireNonNull(state.rooms().get(0));
        assertEquals(new BigDecimal("22"), parsed.homeSetpoint());
        assertEquals(new BigDecimal("17"), parsed.awaySetpoint());
        assertEquals(new BigDecimal("20"), parsed.manualSetpoint());
        assertEquals("Schedule", parsed.regulationType());
        for (var entry : Map.of("home-setpoint", "atHomeSetpoint", "away-setpoint", "awaySetpoint", "manual-setpoint",
                "manualSetpoint").entrySet()) {
            var patch = state.patch(0, entry.getKey(), new QuantityType<>("68 °F"));
            assertEquals("/api/rooms/0", patch.uri());
            assertEquals(JsonParser.parseString("{\"id\":0,\"regulation\":{\"" + entry.getValue() + "\":20}}"),
                    patch.body());
            assertThrows(IllegalArgumentException.class, () -> state.patch(0, entry.getKey(), new DecimalType(40)));
        }
        assertEquals(JsonParser.parseString("{\"id\":0,\"setpoint\":{\"temperature\":21}}"),
                state.patch(0, "setpoint", new DecimalType(21)).body());
    }

    @Test
    void manualModeUsesDifferentRoomAndZoneRequests() throws Exception {
        var json = world();
        var room = json.getAsJsonArray("allRooms").get(0).getAsJsonObject();
        room.addProperty("zone", "None");
        for (var mode : org.openhab.core.library.types.OnOffType.values()) {
            String type = mode == org.openhab.core.library.types.OnOffType.ON ? "Manual" : "Schedule";
            room.addProperty("zone", "None");
            var patch = WorldState.parse(json).patch(0, "manual", mode);
            assertEquals("/api/rooms/0", patch.uri());
            assertEquals(JsonParser.parseString("{\"id\":0,\"regulation\":{\"type\":\"" + type + "\"}}"), patch.body());
            room.addProperty("zone", "Living");
            patch = WorldState.parse(json).patch(0, "manual", mode);
            assertEquals("/api/zones/living", patch.uri());
            assertEquals(JsonParser.parseString("{\"name\":\"Living\",\"regulationType\":\"" + type + "\"}"),
                    patch.body());
        }
        assertThrows(IllegalArgumentException.class,
                () -> WorldState.parse(json).patch(0, "manual", new DecimalType(1)));
        room.remove("zone");
        assertThrows(IllegalArgumentException.class,
                () -> WorldState.parse(json).patch(0, "manual", org.openhab.core.library.types.OnOffType.ON));
        room.addProperty("zone", "Unexpected");
        assertThrows(IllegalArgumentException.class,
                () -> WorldState.parse(json).patch(0, "manual", org.openhab.core.library.types.OnOffType.OFF));
    }

    @Test
    void absentRegulationDoesNotInventPersistentSettings() throws Exception {
        var parsed = java.util.Objects.requireNonNull(WorldState.parse(world()).rooms().get(0));
        assertNull(parsed.homeSetpoint());
        assertNull(parsed.awaySetpoint());
        assertNull(parsed.manualSetpoint());
        assertNull(parsed.regulationType());
        assertNull(parsed.zone());
    }

    @Test
    void livingZoneMembershipUsesRoomPatchWithoutTemperatureRequirements() throws Exception {
        var json = world();
        json.remove("temperatureSettings");
        var room = json.getAsJsonArray("allRooms").get(0).getAsJsonObject();
        room.remove("minSetpoint");
        room.remove("maxSetpoint");
        for (var mode : org.openhab.core.library.types.OnOffType.values()) {
            String zone = mode == org.openhab.core.library.types.OnOffType.ON ? "Living" : "None";
            var patch = WorldState.parse(json).patch(0, "living-zone", mode);
            assertEquals("/api/rooms/0", patch.uri());
            assertEquals(JsonParser.parseString("{\"id\":0,\"zone\":\"" + zone + "\"}"), patch.body());
        }
        assertThrows(IllegalArgumentException.class,
                () -> WorldState.parse(json).patch(0, "living-zone", new DecimalType(1)));
        assertThrows(IllegalArgumentException.class,
                () -> WorldState.parse(json).patch(1, "living-zone", org.openhab.core.library.types.OnOffType.ON));
    }

    @Test
    void livingZoneStateRequiresConsistentKnownMemberModes() throws Exception {
        var json = JsonParser.parseString("""
                {"allRooms":[
                {"id":0,"zone":"Living","regulation":{"type":"Manual"}},
                {"id":1,"zone":"Living","regulation":{"type":"Manual"}},
                {"id":2,"zone":"None","regulation":{"type":"Schedule"}}]}
                """).getAsJsonObject();
        assertEquals(org.openhab.core.library.types.OnOffType.ON, WorldState.parse(json).livingZoneManualState());
        var rooms = json.getAsJsonArray("allRooms");
        rooms.get(0).getAsJsonObject().getAsJsonObject("regulation").addProperty("type", "Schedule");
        assertEquals(org.openhab.core.types.UnDefType.UNDEF, WorldState.parse(json).livingZoneManualState());
        rooms.get(1).getAsJsonObject().getAsJsonObject("regulation").addProperty("type", "Schedule");
        assertEquals(org.openhab.core.library.types.OnOffType.OFF, WorldState.parse(json).livingZoneManualState());
        rooms.get(1).getAsJsonObject().getAsJsonObject("regulation").addProperty("type", "Unknown");
        assertEquals(org.openhab.core.types.UnDefType.UNDEF, WorldState.parse(json).livingZoneManualState());
        rooms.get(1).getAsJsonObject().remove("regulation");
        assertEquals(org.openhab.core.types.UnDefType.UNDEF, WorldState.parse(json).livingZoneManualState());
        rooms.get(0).getAsJsonObject().addProperty("zone", "None");
        rooms.get(1).getAsJsonObject().addProperty("zone", "None");
        assertEquals(org.openhab.core.types.UnDefType.UNDEF, WorldState.parse(json).livingZoneManualState());
        for (var mode : org.openhab.core.library.types.OnOffType.values()) {
            var patch = WorldState.livingZonePatch("manual", mode);
            assertEquals("/api/zones/living", patch.uri());
            assertEquals("Living", patch.body().get("name").getAsString());
            assertEquals(mode == org.openhab.core.library.types.OnOffType.ON ? "Manual" : "Schedule",
                    patch.body().get("regulationType").getAsString());
        }
        assertThrows(IllegalArgumentException.class, () -> WorldState.livingZonePatch("manual", new DecimalType(1)));
        assertThrows(IllegalArgumentException.class,
                () -> WorldState.livingZonePatch("setpoint", org.openhab.core.library.types.OnOffType.ON));
    }
}
