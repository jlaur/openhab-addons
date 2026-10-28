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
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.Future;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.openhab.binding.danfosslink.internal.protocol.Grid;
import org.openhab.binding.danfosslink.internal.protocol.Identity;
import org.openhab.binding.danfosslink.internal.protocol.Operation;
import org.openhab.binding.danfosslink.internal.protocol.Transport;

import com.google.gson.JsonParser;

/**
 * Alarm reporting preserves unknown fields and isolates malformed optional data from heating polling.
 *
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
class AlarmsTest {
    @Test
    void preservesReadAlarmsAndUnknownFields() throws IOException {
        String json = """
                [{"id":42,"isRead":true,"createdDate":"unverified date","level":"UnknownSeverity",
                  "descriptionTextId":"unknown-id","parameters":{"device":"test"}},
                 {"id":43,"isRead":false}]
                """;
        var alarms = Objects.requireNonNull(WorldState.parse(
                JsonParser.parseString("{\"allRooms\":[],\"allUnresolvedAlarms\":" + json + "}").getAsJsonObject())
                .alarms());
        assertEquals(2, alarms.count());
        assertEquals(JsonParser.parseString(json), JsonParser.parseString(alarms.json()));
        var empty = Objects.requireNonNull(WorldState
                .parse(JsonParser.parseString("{\"allRooms\":[],\"allUnresolvedAlarms\":[]}").getAsJsonObject())
                .alarms());
        assertEquals(0, empty.count());
        assertEquals("[]", empty.json());
    }

    @Test
    void unavailableAlarmsDoNotDiscardHeatingState() throws IOException {
        for (String value : new String[] { "null", "{}", "false", "[null]", "[{}]", "[42]", "[{\"id\":{}}]",
                "[{\"id\":1},{\"id\":2,\"isRead\":[]}]" }) {
            var world = WorldState.parse(JsonParser.parseString(
                    "{\"allRooms\":[{\"id\":2,\"ambientTemperature\":21}],\"allUnresolvedAlarms\":" + value + "}")
                    .getAsJsonObject());
            assertNull(world.alarms(), value);
            assertEquals(21, Objects.requireNonNull(world.rooms().get(2).temperature()).intValue());
        }
        assertNull(WorldState.parse(JsonParser.parseString("{\"allRooms\":[]}").getAsJsonObject()).alarms());
    }

    @Test
    void dedicatedReadUsesSeparateEndpointAndRejectsMalformedCollection() throws Exception {
        var peer = mock(Transport.class);
        when(peer.receive()).thenReturn(response(1, "[{\"id\":42}]"), response(2, "null"));
        try (var grids = mockStatic(Grid.class)) {
            grids.when(() -> Grid.connect(any(), any(), any())).thenReturn(mock(Grid.class));
            try (var client = new CloudClient(mock(Identity.class), new Operation());
                    var session = client.new Session(peer, mock(Future.class))) {
                assertEquals(1, session.alarms().count());
                assertThrows(IOException.class, session::alarms);
                var requests = ArgumentCaptor.forClass(byte[].class);
                verify(peer, times(2)).send(requests.capture());
                var request = JsonParser
                        .parseString(new String(requests.getAllValues().getFirst(), StandardCharsets.UTF_8))
                        .getAsJsonObject().getAsJsonObject("data").getAsJsonObject("data");
                assertEquals("GET", request.get("method").getAsString());
                assertEquals("/api/alarms", request.get("uri").getAsString());
                assertFalse(request.has("content"));
            }
        }
    }

    private static byte[] response(int id, String content) {
        return ("{\"id\":" + id + ",\"data\":{\"type\":\"JsonWebResponse\",\"data\":{\"status\":200,\"content\":"
                + content + "}}}").getBytes(StandardCharsets.UTF_8);
    }
}
