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

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.binding.danfosslink.internal.protocol.ControllerTime;

/**
 * Bidirectional deadline conversion independent of timestamp timezone and transport delay.
 *
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
class ControllerTimeTest {
    private static final Instant NOW = Instant.parse("2026-10-17T00:48:00Z");

    @Test
    void aheadAndBehindClocksPreserveRemainingTimeAndIntervals() {
        for (long seconds : new long[] { -1500, 0, 1500 }) {
            var time = ControllerTime
                    .observed(NOW.plusSeconds(seconds).atOffset(ZoneOffset.ofHours(2)).toZonedDateTime(), NOW);
            var start = NOW.plusSeconds(300);
            var end = start.plusSeconds(3600);
            assertEquals(Duration.ofSeconds(seconds), time.skew());
            assertEquals(start.plusSeconds(seconds), time.toController(start));
            assertEquals(start, time.toOpenHab(time.toController(start)));
            assertEquals(Duration.ofHours(1), Duration.between(time.toController(start), time.toController(end)));
            var mode = new WorldState.Mode("Planned", time.toController(start), time.toController(end),
                    ZoneOffset.ofHours(2));
            assertEquals(new WorldState.Mode("Planned", start, end, ZoneOffset.ofHours(2)), time.toOpenHab(mode));
        }
    }

    @Test
    void conversionUsesResponseTimeAndPreservesPrecision() {
        var time = ControllerTime.observed(NOW.plusSeconds(1500).atZone(ZoneOffset.UTC), NOW.plusMillis(250));
        var deadline = NOW.plusSeconds(300);
        assertEquals(Instant.parse("2026-10-17T01:17:59.750Z"), time.toController(deadline));
        assertEquals(deadline.plusMillis(250), time.toOpenHab(NOW.plusSeconds(1800)));
        assertNull(time.toOpenHab((WorldState.Mode) null));
        assertEquals(new WorldState.Mode("Active", null, null, null),
                time.toOpenHab(new WorldState.Mode("Active", null, null, null)));
    }

    @Test
    void missingClockIsUnadjusted() {
        assertEquals(NOW, ControllerTime.observed(null, NOW).toController(NOW));
    }
}
