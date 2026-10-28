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
import java.util.List;
import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.core.library.types.DateTimeType;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;

/**
 * Pending-command replacement, cancellation, batch ordering, and isolation from subsequent commands.
 *
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
class PendingCommandsTest {
    @Test
    void replacementsPreserveOtherTargetsAndMoveToTheEndOfTheirKind() {
        var queue = new PendingCommands();
        var first = new PendingCommand.Patch(1, CHANNEL_SETPOINT, new DecimalType(20));
        var replacement = new PendingCommand.Patch(1, CHANNEL_SETPOINT, new DecimalType(22));
        var otherRoom = new PendingCommand.Patch(2, CHANNEL_SETPOINT, new DecimalType(21));
        var otherChannel = new PendingCommand.Patch(1, CHANNEL_HOME_SETPOINT, new DecimalType(23));
        queue.add(first);
        queue.add(otherRoom);
        queue.add(otherChannel);
        queue.add(replacement);
        assertEquals(List.of(otherRoom, otherChannel, replacement), queue.drain());
        assertTrue(queue.isEmpty());
    }

    @Test
    void vacationEditsKeepLatestBoundaryOrderAndCancellationLeavesOtherCommands() {
        var queue = new PendingCommands();
        var start = edit(CHANNEL_VACATION_START, "2027-01-01T00:00:00Z");
        var end = edit(CHANNEL_VACATION_END, "2027-02-01T00:00:00Z");
        var replacement = edit(CHANNEL_VACATION_START, "2027-01-02T00:00:00Z");
        queue.add(start);
        queue.add(end);
        queue.add(replacement);
        assertEquals(List.of(end, replacement), queue.drain());
        var temperature = new PendingCommand.Patch(null, CHANNEL_VACATION_TEMPERATURE, new DecimalType(15));
        var home = new PendingCommand.Home(new HomeSelection.Request(false, Set.of(), false));
        var off = new PendingCommand.Vacation(new VacationState.Request(CHANNEL_VACATION, OnOffType.OFF, null, null));
        queue.add(start);
        queue.add(end);
        queue.add(new PendingCommand.Vacation(new VacationState.Request(CHANNEL_VACATION, OnOffType.ON, null, null)));
        queue.add(temperature);
        queue.add(home);
        queue.add(off);
        assertEquals(List.of(off, home, temperature), queue.drain());
    }

    @Test
    void pauseAndHomeReplaceTheirPreviousIntentAndBatchPriorityIsPreserved() {
        var queue = new PendingCommands();
        var patch = new PendingCommand.Patch(1, CHANNEL_MANUAL, OnOffType.ON);
        var home = new PendingCommand.Home(new HomeSelection.Request(true, Set.of(1), false));
        var latestHome = new PendingCommand.Home(new HomeSelection.Request(false, Set.of(), false));
        var pause = new PendingCommand.Pause(PauseState.command(CHANNEL_PAUSE, OnOffType.ON, Instant.EPOCH));
        var latestPause = new PendingCommand.Pause(PauseState.command(CHANNEL_PAUSE_UNTIL,
                new DateTimeType(Instant.EPOCH.plusSeconds(3600)), Instant.EPOCH));
        var vacation = new PendingCommand.Vacation(
                new VacationState.Request(CHANNEL_VACATION, OnOffType.OFF, null, null));
        queue.add(patch);
        queue.add(home);
        queue.add(pause);
        queue.add(vacation);
        queue.add(latestPause);
        queue.add(latestHome);
        assertEquals(List.of(vacation, latestPause, latestHome, patch), queue.drain());
    }

    @Test
    void drainedBatchCannotBeReplacedOrCancelledAndClearDiscardsOnlyPendingWork() {
        var queue = new PendingCommands();
        var on = new PendingCommand.Vacation(new VacationState.Request(CHANNEL_VACATION, OnOffType.ON, null, null));
        var off = new PendingCommand.Vacation(new VacationState.Request(CHANNEL_VACATION, OnOffType.OFF, null, null));
        queue.add(on);
        var batch = queue.drain();
        queue.add(off);
        assertEquals(List.of(on), batch);
        assertTrue(queue.contains(PendingCommand.Kind.VACATION));
        queue.clear();
        assertEquals(List.of(on), batch);
        assertTrue(queue.drain().isEmpty());
        assertFalse(queue.contains(PendingCommand.Kind.VACATION));
    }

    private static PendingCommand.Vacation edit(String channel, String value) {
        return new PendingCommand.Vacation(
                new VacationState.Request(channel, new DateTimeType(Instant.parse(value)), null, null));
    }
}
