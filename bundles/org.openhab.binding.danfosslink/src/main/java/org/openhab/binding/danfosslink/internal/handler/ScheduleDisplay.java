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

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Consumer;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.danfosslink.internal.WorldState;
import org.openhab.core.types.State;
import org.openhab.core.types.UnDefType;

/**
 * Child schedule refresh timing and rejection of responses from obsolete lifecycles or selections.
 *
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
final class ScheduleDisplay {
    private final Object lifecycle;
    private final Consumer<State> publish;
    private boolean running;
    private long revision;
    private @Nullable Integer source;
    private Instant nextRead = Instant.MIN;

    ScheduleDisplay(Object lifecycle, Consumer<State> publish) {
        this.lifecycle = lifecycle;
        this.publish = publish;
    }

    void start() {
        synchronized (lifecycle) {
            running = true;
            source = null;
            invalidate();
        }
    }

    void stop() {
        synchronized (lifecycle) {
            running = false;
            source = null;
            invalidate();
        }
    }

    // Refresh the same source without discarding its last reported state while the read is pending.
    void invalidate() {
        synchronized (lifecycle) {
            revision++;
            nextRead = Instant.MIN;
        }
    }

    record Request(int roomId, Consumer<State> complete) {
    }

    @Nullable
    Request request(@Nullable Integer roomId, boolean linked, Instant now) {
        synchronized (lifecycle) {
            if (!running || !linked) {
                return null;
            }
            if (!Objects.equals(source, roomId)) {
                invalidate();
                source = roomId;
                publish.accept(UnDefType.UNDEF);
            }
            if (roomId == null) {
                publish.accept(UnDefType.UNDEF);
                return null;
            }
            if (now.isBefore(nextRead)) {
                return null;
            }
            long requestedRevision = revision;
            return new Request(roomId, state -> {
                synchronized (lifecycle) {
                    if (running && revision == requestedRevision) {
                        // Failed reads are retried, but not on every heating command or short polling interval.
                        nextRead = now.plus(state == UnDefType.UNDEF ? Duration.ofMinutes(1) : Duration.ofMinutes(15));
                        publish.accept(state);
                    }
                }
            });
        }
    }

    static @Nullable Integer source(WorldState world, @Nullable Integer roomId) {
        WorldState.Room room = roomId == null ? null : world.rooms().get(roomId);
        if (roomId != null && room == null) {
            return null;
        }
        if (room != null && "None".equals(room.zone())) {
            return roomId;
        }
        if (room != null && !"Living".equals(room.zone())) {
            return null;
        }
        return world.rooms().values().stream().filter(member -> "Living".equals(member.zone())).map(WorldState.Room::id)
                .min(Integer::compareTo).orElse(null);
    }
}
