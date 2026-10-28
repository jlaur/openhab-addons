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

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Objects;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.binding.danfosslink.internal.WorldState;
import org.openhab.core.library.types.StringType;
import org.openhab.core.types.State;
import org.openhab.core.types.UnDefType;

import com.google.gson.JsonParser;

/**
 * Schedule polling and stale-response rejection without timing-dependent sleeps.
 *
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
class ScheduleDisplayTest {
    @Test
    void readsOnlyLinkedSchedulesAndRefreshOverridesInterval() {
        var states = new ArrayList<State>();
        var display = new ScheduleDisplay(new Object(), states::add);
        Instant now = Instant.parse("2026-10-28T12:00:00Z");
        assertNull(display.request(2, true, now));
        display.start();
        assertNull(display.request(2, false, now));
        var read = Objects.requireNonNull(display.request(2, true, now));
        read.complete().accept(new StringType("{}"));
        assertNull(display.request(2, true, now.plus(Duration.ofMinutes(14))));
        assertNotNull(display.request(2, true, now.plus(Duration.ofMinutes(15))));
        display.invalidate();
        assertNotNull(display.request(2, true, now));
    }

    @Test
    void failuresRetryAndOldResponsesCannotReplaceNewSelectionOrLifecycle() {
        var states = new ArrayList<State>();
        var display = new ScheduleDisplay(new Object(), states::add);
        Instant now = Instant.EPOCH;
        display.start();
        var old = Objects.requireNonNull(display.request(2, true, now));
        var fresh = Objects.requireNonNull(display.request(3, true, now));
        states.clear();
        old.complete().accept(new StringType("obsolete"));
        assertTrue(states.isEmpty());
        fresh.complete().accept(UnDefType.UNDEF);
        assertNull(display.request(3, true, now.plusSeconds(59)));
        assertNotNull(display.request(3, true, now.plusSeconds(60)));
        display.stop();
        display.start();
        states.clear();
        fresh.complete().accept(new StringType("obsolete"));
        assertTrue(states.isEmpty());
        var pending = Objects.requireNonNull(display.request(3, true, now));
        display.invalidate();
        states.clear();
        pending.complete().accept(new StringType("before refresh"));
        assertTrue(states.isEmpty());
        assertNotNull(display.request(3, true, now));
        assertNull(display.request(null, true, now));
        assertEquals(UnDefType.UNDEF, states.getLast());
    }

    @Test
    void refreshRetainsReportedStateUntilReadbackSucceedsOrFails() {
        var states = new ArrayList<State>();
        var display = new ScheduleDisplay(new Object(), states::add);
        Instant now = Instant.EPOCH;
        var previous = new StringType("previous schedule");
        var updated = new StringType("updated schedule");
        display.start();
        Objects.requireNonNull(display.request(2, true, now)).complete().accept(previous);
        states.clear();
        display.invalidate();
        var readback = Objects.requireNonNull(display.request(2, true, now));
        assertTrue(states.isEmpty(), "Starting a refresh must not publish UNDEF");
        readback.complete().accept(updated);
        assertEquals(java.util.List.of(updated), states);
        states.clear();
        display.invalidate();
        var failure = Objects.requireNonNull(display.request(2, true, now));
        assertTrue(states.isEmpty());
        failure.complete().accept(UnDefType.UNDEF);
        assertEquals(java.util.List.of(UnDefType.UNDEF), states);
    }

    @Test
    void refreshStillInvalidatesChangedOrMissingSourcesAndRejectsEarlierReads() {
        var states = new ArrayList<State>();
        var display = new ScheduleDisplay(new Object(), states::add);
        Instant now = Instant.EPOCH;
        display.start();
        var old = Objects.requireNonNull(display.request(2, true, now));
        display.invalidate();
        states.clear();
        var current = Objects.requireNonNull(display.request(2, true, now));
        old.complete().accept(new StringType("obsolete"));
        assertTrue(states.isEmpty());
        current.complete().accept(new StringType("current"));
        states.clear();
        display.invalidate();
        assertNotNull(display.request(3, true, now));
        assertEquals(java.util.List.of(UnDefType.UNDEF), states);
        states.clear();
        assertNull(display.request(null, true, now));
        assertFalse(states.isEmpty());
        assertTrue(states.stream().allMatch(UnDefType.UNDEF::equals));
    }

    @Test
    void sharedSchedulesUseOneDeterministicMemberAndMissingMembershipIsUnknown() throws Exception {
        var world = WorldState.parse(JsonParser.parseString("""
                {"allRooms":[{"id":4,"zone":"Living"},{"id":2,"zone":"Living"},
                {"id":1,"zone":"None"},{"id":3}]}
                """).getAsJsonObject());
        assertEquals(2, ScheduleDisplay.source(world, null));
        assertEquals(2, ScheduleDisplay.source(world, 4));
        assertEquals(1, ScheduleDisplay.source(world, 1));
        assertNull(ScheduleDisplay.source(world, 3));
        assertNull(ScheduleDisplay.source(world, 99));
    }
}
