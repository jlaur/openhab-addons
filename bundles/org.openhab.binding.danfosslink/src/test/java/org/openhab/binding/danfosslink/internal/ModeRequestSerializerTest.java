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

import java.time.Instant;
import java.time.ZoneOffset;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.binding.danfosslink.internal.protocol.dto.ModeRequestDTO;
import org.openhab.binding.danfosslink.internal.protocol.serialization.ModeRequestSerializer;

import com.google.gson.JsonParser;

/**
 * Controller-time formatting without adjusting deadlines or retaining another controller timestamp format.
 *
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
class ModeRequestSerializerTest {
    @Test
    void formattingPreservesControllerTimeAndLeavesDtoUnchanged() {
        var request = new ModeRequestDTO("Vacation", "Active");
        var start = Instant.parse("2026-10-17T12:00:00.123Z");
        var end = start.plusSeconds(3600);
        request.startTime = start;
        request.endTime = end;
        var local = ModeRequestSerializer.serialize(request, ZoneOffset.ofHours(2));
        assertEquals(JsonParser.parseString("""
                {"type":"Vacation","state":"Active","startTime":"2026-10-17T14:00:00","endTime":"2026-10-17T15:00:00"}
                """), local);
        assertEquals(JsonParser.parseString("""
                {"type":"Vacation","state":"Active","startTime":"2026-10-17T12:00:00Z","endTime":"2026-10-17T13:00:00Z"}
                """), ModeRequestSerializer.serialize(request, null));
        assertEquals(local, ModeRequestSerializer.serialize(request, ZoneOffset.ofHours(2)));
        assertEquals(start, request.startTime);
        assertEquals(end, request.endTime);
    }

    @Test
    void formattingRejectsOutOfRangeDeadlinesAndOmitsNullDates() {
        var request = new ModeRequestDTO("Pause", "Active");
        request.endTime = Instant.parse("2100-01-01T12:00:00Z");
        assertThrows(IllegalArgumentException.class, () -> ModeRequestSerializer.serialize(request, null));
        assertEquals(JsonParser.parseString("""
                {"type":"Pause","state":"Inactive"}
                """), ModeRequestSerializer.serialize(new ModeRequestDTO("Pause", "Inactive"), null));
    }
}
