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
import java.time.LocalTime;
import java.util.Objects;
import java.util.concurrent.Future;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.openhab.binding.danfosslink.internal.exception.ControllerResponseException;
import org.openhab.binding.danfosslink.internal.protocol.Grid;
import org.openhab.binding.danfosslink.internal.protocol.Identity;
import org.openhab.binding.danfosslink.internal.protocol.Operation;
import org.openhab.binding.danfosslink.internal.protocol.Transport;
import org.openhab.binding.danfosslink.internal.protocol.dto.WeeklyScheduleDTO;

import com.google.gson.JsonParser;

/**
 * Schedule API envelopes and response failures, without connecting to a controller.
 *
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
class CloudClientScheduleTest {
    private static byte[] response(int id, int status, String content) {
        return ("{\"id\":" + id + ",\"data\":{\"type\":\"JsonWebResponse\",\"data\":{\"status\":" + status
                + ",\"content\":" + content + "}}}").getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void readsDetailedRoomAndWritesRoomAndZoneSchedules() throws Exception {
        var peer = mock(Transport.class);
        when(peer.receive()).thenReturn(response(1, 200, """
                {"id":2,"schedule":{"monday":[{"state":"AtHome","startTimeFromMidnight":"06:00:00",
                "endTimeFromMidnight":"1.00:00:00"}]}}
                """), response(2, 200, "null"), response(3, 200, "null"));
        try (var grids = mockStatic(Grid.class)) {
            grids.when(() -> Grid.connect(any(), any(), any())).thenReturn(mock(Grid.class));
            try (var client = new CloudClient(mock(Identity.class), new Operation());
                    var session = client.new Session(peer, mock(Future.class))) {
                var room = session.room(2);
                var schedule = Objects.requireNonNull(room.schedule);
                assertEquals(LocalTime.MIDNIGHT,
                        Objects.requireNonNull(Objects.requireNonNull(schedule.monday).getFirst()).endTimeFromMidnight);
                session.roomSchedule(2, schedule);
                session.livingZoneSchedule(schedule);
                var requests = ArgumentCaptor.forClass(byte[].class);
                verify(peer, times(3)).send(requests.capture());
                var sent = requests.getAllValues();
                var read = JsonParser.parseString(new String(sent.get(0), StandardCharsets.UTF_8)).getAsJsonObject()
                        .getAsJsonObject("data").getAsJsonObject("data");
                assertEquals("GET", read.get("method").getAsString());
                assertEquals("/api/rooms/2", read.get("uri").getAsString());
                assertFalse(read.has("content"));
                for (int i = 1; i <= 2; i++) {
                    var write = JsonParser.parseString(new String(sent.get(i), StandardCharsets.UTF_8))
                            .getAsJsonObject().getAsJsonObject("data").getAsJsonObject("data");
                    assertEquals("PATCH", write.get("method").getAsString());
                    assertEquals(i == 1 ? "/api/rooms/2" : "/api/zones/living", write.get("uri").getAsString());
                    var content = write.getAsJsonObject("content");
                    assertEquals(2, content.size());
                    assertEquals(i == 1 ? "2" : "Living", content.get(i == 1 ? "id" : "name").getAsString());
                    assertEquals(2, content.getAsJsonObject("schedule").getAsJsonArray("monday").size());
                }
            }
        }
    }

    @Test
    void invalidReadsAndRejectedWritesRemainCheckedFailures() throws Exception {
        var peer = mock(Transport.class);
        try (var grids = mockStatic(Grid.class)) {
            grids.when(() -> Grid.connect(any(), any(), any())).thenReturn(mock(Grid.class));
            try (var client = new CloudClient(mock(Identity.class), new Operation());
                    var session = client.new Session(peer, mock(Future.class))) {
                assertThrows(IllegalArgumentException.class, () -> session.room(-1));
                verifyNoInteractions(peer);
                int id = 0;
                for (String content : new String[] { "null", "[]", "{}", "{\"id\":3}", "{\"id\":2,\"schedule\":[]}",
                        "{\"id\":2,\"schedule\":{\"monday\":[{\"startTimeFromMidnight\":\"bad\"}]}}" }) {
                    when(peer.receive()).thenReturn(response(++id, 200, content));
                    assertThrows(IOException.class, () -> session.room(2));
                }
                when(peer.receive()).thenReturn(response(++id, 200, "{\"id\":2}"));
                assertNull(session.room(2).schedule);
                var schedule = Objects
                        .requireNonNull(ProtocolJson.GSON.fromJson("{\"monday\":[]}", WeeklyScheduleDTO.class));
                when(peer.receive()).thenReturn(response(++id, 400, "{\"errorMessages\":[\"Invalid schedule\"]}"));
                assertThrows(ControllerResponseException.class, () -> session.roomSchedule(2, schedule));
            }
        }
    }
}
