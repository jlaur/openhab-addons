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

import java.time.LocalDateTime;
import java.time.ZoneOffset;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.binding.danfosslink.internal.protocol.dto.ModeRequestDTO;
import org.openhab.binding.danfosslink.internal.protocol.dto.ModeState;
import org.openhab.binding.danfosslink.internal.protocol.dto.ModeType;

import com.google.gson.JsonParser;

/**
 * Controller-time formatting without adjusting deadlines or retaining another controller timestamp format.
 *
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
class CloudClientModeTest {
    @Test
    void serializationPreservesPreparedLocalDatesAndOmitsNulls() {
        var request = new ModeRequestDTO(ModeType.VACATION, ModeState.ACTIVE);
        var start = LocalDateTime.parse("2026-12-24T08:24:53");
        var end = LocalDateTime.parse("2026-12-24T16:24:53");
        request.startTime = start;
        request.endTime = end;
        var json = JsonParser.parseString("""
                {"type":"Vacation","state":"Active","startTime":"2026-12-24T08:24:53","endTime":"2026-12-24T16:24:53"}
                """);
        assertEquals(json, CloudClient.modeContent(request));
        var readback = org.openhab.binding.danfosslink.internal.ProtocolJson.GSON.fromJson(json, ModeRequestDTO.class);
        assertEquals(start, readback.startTime);
        assertEquals(end, readback.endTime);
        assertEquals(start, request.startTime);
        assertEquals(end, request.endTime);
        assertEquals(JsonParser.parseString("""
                {"type":"Pause","state":"Inactive"}
                """), CloudClient.modeContent(new ModeRequestDTO(ModeType.PAUSE, ModeState.INACTIVE)));
    }

    @Test
    void requestPreparationRejectsSentinelDeadlines() {
        var time = org.openhab.binding.danfosslink.internal.protocol.ControllerTime.UNADJUSTED;
        for (String value : new String[] { "1969-12-31T12:00:00Z", "2100-01-01T12:00:00Z" }) {
            assertThrows(IllegalArgumentException.class,
                    () -> time.toControllerLocal(java.time.Instant.parse(value), ZoneOffset.UTC));
        }
    }

    @Test
    void modeEnumsPreserveWireValuesInBothDirections() {
        var gson = org.openhab.binding.danfosslink.internal.ProtocolJson.GSON;
        var types = java.util.Map.of(ModeType.AT_HOME, "AtHome", ModeType.PAUSE, "Pause", ModeType.VACATION,
                "Vacation");
        var states = java.util.Map.of(ModeState.INACTIVE, "Inactive", ModeState.PLANNED, "Planned", ModeState.ACTIVE,
                "Active");
        types.forEach((type, typeValue) -> states.forEach((state, stateValue) -> {
            var json = new com.google.gson.JsonObject();
            json.addProperty("type", typeValue);
            json.addProperty("state", stateValue);
            assertEquals(json, CloudClient.modeContent(new ModeRequestDTO(type, state)));
            var parsed = gson.fromJson(json, ModeRequestDTO.class);
            assertEquals(type, parsed.type);
            assertEquals(state, parsed.state);
            assertEquals(stateValue, state.toString());
        }));
        assertNull(gson.fromJson("\"Unknown\"", ModeState.class));
        assertNull(gson.fromJson("null", ModeState.class));
        assertNull(gson.fromJson("\"Unknown\"", ModeType.class));
    }
}
