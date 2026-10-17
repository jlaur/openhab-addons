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
import java.util.Objects;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.binding.danfosslink.internal.WorldState.Mode;
import org.openhab.binding.danfosslink.internal.protocol.ControllerTime;
import org.openhab.core.library.types.DateTimeType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.types.UnDefType;

import com.google.gson.JsonParser;

/**
 * Vacation staging, interval validation and reconciliation with controller state.
 *
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
class VacationStateTest {
    private static final Instant NOW = Instant.parse("2026-10-16T12:00:00Z");
    private static final Instant START = NOW.plusSeconds(3600);
    private static final Instant END = NOW.plusSeconds(7200);
    private static final Mode INACTIVE = new Mode("Inactive", null, null, null);

    @Test
    void inactiveStagingSurvivesPollingAndOnRequiresCompleteValidDates() {
        var state = new VacationState();
        assertEquals(UnDefType.UNDEF, state.state(CHANNEL_VACATION));
        assertThrows(IllegalArgumentException.class, () -> state.command(CHANNEL_VACATION, OnOffType.ON, NOW));
        state.report(INACTIVE);
        var immediate = Objects.requireNonNull(state.command(CHANNEL_VACATION, OnOffType.ON, NOW)).body(INACTIVE, NOW,
                ControllerTime.UNADJUSTED);
        assertEquals(JsonParser.parseString("""
                {"type":"Vacation","state":"Active"}
                """), immediate);
        assertFalse(immediate.has("startTime"));
        assertFalse(immediate.has("endTime"));
        assertNull(state.command(CHANNEL_VACATION_START, new DateTimeType(START), NOW));
        state.report(INACTIVE);
        assertEquals(new DateTimeType(START), state.state(CHANNEL_VACATION_START));
        assertThrows(IllegalArgumentException.class, () -> state.command(CHANNEL_VACATION, OnOffType.ON, NOW));
        state.command(CHANNEL_VACATION_END, new DateTimeType(START), NOW);
        assertThrows(IllegalArgumentException.class, () -> state.command(CHANNEL_VACATION, OnOffType.ON, NOW));
        state.command(CHANNEL_VACATION_END, new DateTimeType(END), NOW);
        var request = Objects.requireNonNull(state.command(CHANNEL_VACATION, OnOffType.ON, NOW));
        assertEquals(START.toString(),
                request.body(INACTIVE, NOW, ControllerTime.UNADJUSTED).get("startTime").getAsString());
        assertEquals(END.toString(),
                request.body(INACTIVE, NOW, ControllerTime.UNADJUSTED).get("endTime").getAsString());
        assertThrows(IllegalArgumentException.class, () -> request.body(INACTIVE, START, ControllerTime.UNADJUSTED));
        assertThrows(IllegalArgumentException.class,
                () -> request.body(new Mode("Active", null, null, null), NOW, ControllerTime.UNADJUSTED));
        var cancel = Objects.requireNonNull(state.command(CHANNEL_VACATION, OnOffType.OFF, NOW)).body(INACTIVE, NOW,
                ControllerTime.UNADJUSTED);
        assertEquals(JsonParser.parseString("""
                {"type":"Vacation","state":"Inactive"}
                """), cancel);
        assertEquals(UnDefType.UNDEF, state.state(CHANNEL_VACATION_START));
        assertEquals(UnDefType.UNDEF, state.state(CHANNEL_VACATION_END));
        state.command(CHANNEL_VACATION_END, new DateTimeType(END), NOW);
        assertThrows(IllegalArgumentException.class, () -> state.command(CHANNEL_VACATION, OnOffType.ON, NOW));
    }

    @Test
    void externalChangesAndReadbackReplaceStagingAndCompletionClearsDates() {
        var state = new VacationState();
        state.report(INACTIVE);
        state.command(CHANNEL_VACATION_START, new DateTimeType(START.plusSeconds(20)), NOW);
        state.report(new Mode("Planned", START, END, null));
        assertEquals(OnOffType.ON, state.state(CHANNEL_VACATION));
        assertEquals(new StringType("Planned"), state.state(CHANNEL_VACATION_STATE));
        assertEquals(new DateTimeType(START), state.state(CHANNEL_VACATION_START));
        assertEquals(new DateTimeType(END), state.state(CHANNEL_VACATION_END));
        state.report(new Mode("Active", START, END, null));
        assertEquals(new StringType("Active"), state.state(CHANNEL_VACATION_STATE));
        assertThrows(IllegalArgumentException.class,
                () -> state.command(CHANNEL_VACATION_END, new DateTimeType(END.plusSeconds(60)), NOW));
        assertNull(state.command(CHANNEL_VACATION, OnOffType.ON, NOW));
        state.report(INACTIVE);
        assertEquals(OnOffType.OFF, state.state(CHANNEL_VACATION));
        assertEquals(UnDefType.UNDEF, state.state(CHANNEL_VACATION_START));
        assertEquals(UnDefType.UNDEF, state.state(CHANNEL_VACATION_END));
        state.report(null);
        assertEquals(UnDefType.UNDEF, state.state(CHANNEL_VACATION_STATE));
    }

    @Test
    void plannedEditsUseLatestCounterpartAndRejectInvalidOrChangedMode() {
        var state = new VacationState();
        var planned = new Mode("Planned", START, END, null);
        state.report(planned);
        Instant laterEnd = END.plusSeconds(7200);
        var endEdit = Objects.requireNonNull(state.command(CHANNEL_VACATION_END, new DateTimeType(laterEnd), NOW));
        var startEdit = Objects.requireNonNull(state.command(CHANNEL_VACATION_START, new DateTimeType(END), NOW));
        assertEquals(laterEnd.toString(),
                endEdit.body(planned, NOW, ControllerTime.UNADJUSTED).get("endTime").getAsString());
        assertThrows(IllegalArgumentException.class, () -> startEdit.body(planned, NOW, ControllerTime.UNADJUSTED));
        var confirmed = new Mode("Planned", START, laterEnd, null);
        var body = startEdit.body(confirmed, NOW, ControllerTime.UNADJUSTED);
        assertEquals(END.toString(), body.get("startTime").getAsString());
        assertEquals(laterEnd.toString(), body.get("endTime").getAsString());
        assertThrows(IllegalArgumentException.class, () -> startEdit.body(INACTIVE, NOW, ControllerTime.UNADJUSTED));
        assertThrows(IllegalArgumentException.class,
                () -> startEdit.body(new Mode("Active", START, END, null), NOW, ControllerTime.UNADJUSTED));
        assertThrows(IllegalArgumentException.class, () -> endEdit.body(planned, START, ControllerTime.UNADJUSTED));
        assertThrows(IllegalArgumentException.class, () -> endEdit.body(null, NOW, ControllerTime.UNADJUSTED));
    }

    @Test
    void requestsUseControllerLocalTimeWithWholeSecondPrecision() {
        var state = new VacationState();
        var inactive = new Mode("Inactive", null, null, java.time.ZoneOffset.ofHours(2));
        state.report(inactive);
        state.command(CHANNEL_VACATION_START, new DateTimeType(START.plusMillis(123)), NOW);
        state.command(CHANNEL_VACATION_END, new DateTimeType(END.plusMillis(456)), NOW);
        var body = Objects.requireNonNull(state.command(CHANNEL_VACATION, OnOffType.ON, NOW)).body(inactive, NOW,
                ControllerTime.UNADJUSTED);
        assertEquals(JsonParser.parseString("""
                {"type":"Vacation","state":"Active","startTime":"2026-10-16T15:00:00","endTime":"2026-10-16T16:00:00"}
                """), body);
        state.command(CHANNEL_VACATION_END, new DateTimeType(START.plusMillis(999)), NOW);
        assertThrows(IllegalArgumentException.class, () -> state.command(CHANNEL_VACATION, OnOffType.ON, NOW));
    }

    @Test
    void stagedDatesAndPlannedEditsUseOpenHabTimeWithConversionOnlyWhenWritten() {
        for (long skew : new long[] { -1500, 1500 }) {
            var time = new ControllerTime(java.time.Duration.ofSeconds(skew));
            var state = new VacationState();
            state.report(INACTIVE);
            state.command(CHANNEL_VACATION_START, new DateTimeType(START), NOW);
            state.command(CHANNEL_VACATION_END, new DateTimeType(END), NOW);
            var body = Objects.requireNonNull(state.command(CHANNEL_VACATION, OnOffType.ON, NOW)).body(INACTIVE, NOW,
                    time);
            assertEquals(START.plusSeconds(skew).toString(), body.get("startTime").getAsString());
            assertEquals(END.plusSeconds(skew).toString(), body.get("endTime").getAsString());
            var reported = time.toOpenHab(new Mode("Planned", START.plusSeconds(skew), END.plusSeconds(skew), null));
            state.report(reported);
            assertEquals(new DateTimeType(START), state.state(CHANNEL_VACATION_START));
            assertEquals(new DateTimeType(END), state.state(CHANNEL_VACATION_END));
            var edit = Objects
                    .requireNonNull(state.command(CHANNEL_VACATION_END, new DateTimeType(END.plusSeconds(600)), NOW));
            body = edit.body(reported, NOW, time);
            assertEquals(START.plusSeconds(skew).toString(), body.get("startTime").getAsString());
            assertEquals(END.plusSeconds(600 + skew).toString(), body.get("endTime").getAsString());
            assertFalse(Objects.requireNonNull(state.command(CHANNEL_VACATION, OnOffType.OFF, NOW))
                    .body(reported, NOW, time).has("endTime"));
        }
    }
}
