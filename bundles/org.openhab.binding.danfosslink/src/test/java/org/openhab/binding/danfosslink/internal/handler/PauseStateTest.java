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
package org.openhab.binding.danfosslink.internal.handler;

import static org.junit.jupiter.api.Assertions.*;
import static org.openhab.binding.danfosslink.internal.DanfossLinkBindingConstants.*;

import java.time.Instant;
import java.time.ZoneOffset;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.binding.danfosslink.internal.WorldState.Mode;
import org.openhab.binding.danfosslink.internal.protocol.ControllerTime;
import org.openhab.core.library.types.DateTimeType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.types.UnDefType;

/**
 * Timed Pause requests, validation and controller readback.
 *
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
class PauseStateTest {
    private static final Instant NOW = Instant.parse("2026-10-17T12:00:00Z");
    private static final Mode INACTIVE = new Mode("Inactive", null, null, null);

    @Test
    void onStartsOneHourAndOffOmitsDates() {
        var on = PauseState.command(CHANNEL_PAUSE, OnOffType.ON, NOW);
        var body = on.body(INACTIVE, NOW.plusSeconds(10), ControllerTime.UNADJUSTED);
        assertEquals("Pause", body.get("type").getAsString());
        assertEquals("Active", body.get("state").getAsString());
        assertEquals(NOW.plusSeconds(3610).toString(), body.get("endTime").getAsString());
        assertFalse(body.has("startTime"));
        assertEquals(body, on.body(new Mode("Active", null, NOW.plusSeconds(60), null), NOW.plusSeconds(10),
                ControllerTime.UNADJUSTED));
        var off = PauseState.command(CHANNEL_PAUSE, OnOffType.OFF, NOW).body(INACTIVE, NOW, ControllerTime.UNADJUSTED);
        assertEquals("Inactive", off.get("state").getAsString());
        assertFalse(off.has("endTime"));
    }

    @Test
    void arbitraryDeadlinesRespectControllerOffsetAndAreRevalidatedBeforeWriting() {
        for (long seconds : new long[] { 30, 300, 5400 }) {
            var end = NOW.plusSeconds(seconds);
            var request = PauseState.command(CHANNEL_PAUSE_UNTIL, new DateTimeType(end.plusNanos(123)), NOW);
            assertEquals(end.toString(),
                    request.body(INACTIVE, NOW, ControllerTime.UNADJUSTED).get("endTime").getAsString());
            var local = new Mode("Active", null, NOW.plusSeconds(3600), ZoneOffset.ofHours(2));
            assertEquals(
                    java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(end.atOffset(ZoneOffset.ofHours(2))),
                    request.body(local, NOW, ControllerTime.UNADJUSTED).get("endTime").getAsString());
            assertThrows(IllegalArgumentException.class, () -> request.body(INACTIVE, end, ControllerTime.UNADJUSTED));
            assertThrows(IllegalArgumentException.class, () -> request.body(null, NOW, ControllerTime.UNADJUSTED));
        }
    }

    @Test
    void invalidAndSentinelDeadlinesAreRejected() {
        for (var end : new Instant[] { NOW.minusSeconds(1), NOW, NOW.plusNanos(1),
                Instant.parse("9999-12-31T00:00:00Z") }) {
            assertThrows(IllegalArgumentException.class,
                    () -> PauseState.command(CHANNEL_PAUSE_UNTIL, new DateTimeType(end), NOW));
        }
        assertThrows(IllegalArgumentException.class, () -> PauseState.command(CHANNEL_PAUSE_UNTIL, OnOffType.ON, NOW));
        assertThrows(IllegalArgumentException.class,
                () -> PauseState.command(CHANNEL_PAUSE, new StringType("ON"), NOW));
    }

    @Test
    void stateReflectsExternalChangesAndExpirationRatherThanTheRequestedDeadline() {
        assertEquals(OnOffType.OFF, PauseState.state(INACTIVE, CHANNEL_PAUSE));
        assertEquals(UnDefType.UNDEF, PauseState.state(INACTIVE, CHANNEL_PAUSE_UNTIL));
        var end = NOW.plusSeconds(60);
        var active = new Mode("Active", null, end, null);
        assertEquals(OnOffType.ON, PauseState.state(active, CHANNEL_PAUSE));
        assertEquals(new DateTimeType(end), PauseState.state(active, CHANNEL_PAUSE_UNTIL));
        assertEquals(UnDefType.UNDEF, PauseState.state(new Mode("Inactive", null, end, null), CHANNEL_PAUSE_UNTIL));
        assertEquals(UnDefType.UNDEF, PauseState.state(new Mode("Active", null, null, null), CHANNEL_PAUSE_UNTIL));
        assertEquals(UnDefType.UNDEF, PauseState.state(null, CHANNEL_PAUSE));
    }

    @Test
    void oneHourAndCustomDeadlineAreRelativeToControllerClock() {
        for (long skew : new long[] { -1500, 1500 }) {
            var time = new ControllerTime(java.time.Duration.ofSeconds(skew));
            var hour = PauseState.command(CHANNEL_PAUSE, OnOffType.ON, NOW).body(INACTIVE, NOW, time);
            assertEquals(NOW.plusSeconds(3600 + skew).toString(), hour.get("endTime").getAsString());
            var custom = PauseState.command(CHANNEL_PAUSE_UNTIL, new DateTimeType(NOW.plusSeconds(300)), NOW)
                    .body(INACTIVE, NOW.plusSeconds(10), time);
            assertEquals(NOW.plusSeconds(300 + skew).toString(), custom.get("endTime").getAsString());
            assertFalse(PauseState.command(CHANNEL_PAUSE, OnOffType.OFF, NOW).body(INACTIVE, NOW, time).has("endTime"));
        }
    }
}
