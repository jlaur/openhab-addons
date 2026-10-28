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

import static org.openhab.binding.danfosslink.internal.DanfossLinkBindingConstants.*;

import java.io.IOException;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.danfosslink.internal.CloudClient;
import org.openhab.binding.danfosslink.internal.WorldState;
import org.openhab.binding.danfosslink.internal.protocol.ControllerTime;
import org.openhab.binding.danfosslink.internal.protocol.HeatingSchedule;
import org.openhab.core.types.Command;

/**
 * Captured command intent, prepared against the latest snapshot immediately before sending.
 *
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
sealed interface PendingCommand {
    // Preserve the existing batch order, independently of arrival order across command kinds.
    enum Kind {
        VACATION,
        PAUSE,
        HOME,
        PATCH,
        SCHEDULE
    }

    record Key(Kind kind, @Nullable Integer roomId, String channel) {
    }

    Key key();

    Prepared prepare(WorldState snapshot, Instant now, ControllerTime time);

    default boolean readBackImmediately() {
        return key().kind() != Kind.PATCH;
    }

    default boolean recoverRejection() {
        // Mode and schedule rejections are read back; existing patch rejection behaviour is unchanged.
        return key().kind() != Kind.PATCH;
    }

    @FunctionalInterface
    interface Write {
        void send(CloudClient.Session session) throws IOException;
    }

    record Prepared(String uri, Write write) {
    }

    record Patch(@Nullable Integer roomId, String channel, Command command) implements PendingCommand {
        @Override
        public Key key() {
            return new Key(Kind.PATCH, roomId, channel);
        }

        WorldState.Patch patch(WorldState snapshot) {
            Integer id = roomId;
            if (id != null) {
                return snapshot.patch(id, channel, command);
            }
            return CHANNEL_VACATION_TEMPERATURE.equals(channel) ? snapshot.vacationTemperaturePatch(command)
                    : WorldState.livingZonePatch(channel, command);
        }

        @Override
        public Prepared prepare(WorldState snapshot, Instant now, ControllerTime time) {
            var body = patch(snapshot);
            return new Prepared(body.uri(), session -> session.apply(body));
        }
    }

    record Schedule(@Nullable Integer roomId, Set<Integer> memberOrigins, String json) implements PendingCommand {
        public Schedule {
            memberOrigins = Set.copyOf(memberOrigins);
        }

        static Schedule capture(@Nullable Integer origin, WorldState snapshot, String json) {
            var parsed = HeatingSchedule.parse(json);
            Integer target = origin;
            Set<Integer> members = Set.of();
            if (origin != null) {
                var room = snapshot.rooms().get(origin);
                if (room == null || (!"None".equals(room.zone()) && !"Living".equals(room.zone()))) {
                    throw new IllegalArgumentException("Room membership unavailable");
                }
                if ("Living".equals(room.zone())) {
                    target = null;
                    members = Set.of(origin);
                }
            }
            var command = new Schedule(target, members, HeatingSchedule.display(parsed));
            command.validate(snapshot);
            return command;
        }

        Schedule merge(Schedule later) {
            var origins = new HashSet<>(memberOrigins);
            origins.addAll(later.memberOrigins);
            return new Schedule(roomId, origins, HeatingSchedule.merge(json, later.json));
        }

        private void validate(WorldState snapshot) {
            Integer id = roomId;
            if (id != null) {
                var room = snapshot.rooms().get(id);
                if (room == null || !"None".equals(room.zone())) {
                    throw new IllegalArgumentException(
                            "Independent room membership changed; submit the schedule again");
                }
            } else {
                if (ScheduleDisplay.source(snapshot, null) == null) {
                    throw new IllegalArgumentException("Living zone has no known members");
                }
                for (int origin : memberOrigins) {
                    var room = snapshot.rooms().get(origin);
                    if (room == null || !"Living".equals(room.zone())) {
                        throw new IllegalArgumentException("Living-zone membership changed; submit the schedule again");
                    }
                }
            }
        }

        @Override
        public Key key() {
            return new Key(Kind.SCHEDULE, roomId, CHANNEL_SCHEDULE);
        }

        @Override
        public Prepared prepare(WorldState snapshot, Instant now, ControllerTime time) {
            validate(snapshot);
            var schedule = HeatingSchedule.parse(json);
            Integer id = roomId;
            return id == null ? new Prepared("/api/zones/living", session -> session.livingZoneSchedule(schedule))
                    : new Prepared("/api/rooms/" + id, session -> session.roomSchedule(id, schedule));
        }
    }

    record Vacation(VacationState.Request request) implements PendingCommand {
        @Override
        public Key key() {
            return new Key(Kind.VACATION, null, request.channel());
        }

        @Override
        public Prepared prepare(WorldState snapshot, Instant now, ControllerTime time) {
            var body = request.body(snapshot.vacation(), now, time);
            return new Prepared("/api/modes/vacation", session -> session.vacation(body));
        }
    }

    record Pause(PauseState.Request request) implements PendingCommand {
        @Override
        public Key key() {
            return new Key(Kind.PAUSE, null, CHANNEL_PAUSE);
        }

        @Override
        public Prepared prepare(WorldState snapshot, Instant now, ControllerTime time) {
            var body = request.body(snapshot.pause(), now, time);
            return new Prepared("/api/modes/pause", session -> session.pause(body));
        }
    }

    record Home(HomeSelection.Request request) implements PendingCommand {
        @Override
        public Key key() {
            return new Key(Kind.HOME, null, CHANNEL_HOME);
        }

        @Override
        public Prepared prepare(WorldState snapshot, Instant now, ControllerTime time) {
            var body = request.body(snapshot);
            return new Prepared("/api/modes/athome", session -> session.home(body));
        }
    }
}
