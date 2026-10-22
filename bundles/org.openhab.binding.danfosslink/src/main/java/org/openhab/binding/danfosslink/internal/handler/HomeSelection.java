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

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.danfosslink.internal.WorldState;
import org.openhab.binding.danfosslink.internal.protocol.dto.ModeRequestDTO;
import org.openhab.binding.danfosslink.internal.protocol.dto.ModeState;
import org.openhab.binding.danfosslink.internal.protocol.dto.ModeType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.types.State;
import org.openhab.core.types.UnDefType;

/**
 * Local Home selection, guarded by the controller lifecycle lock and independent of reported override state.
 *
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
final class HomeSelection {
    private final Set<Integer> rooms = new HashSet<>();
    private boolean livingZone;

    record Request(boolean active, Set<Integer> rooms, boolean livingZone) {
        Request {
            rooms = Set.copyOf(rooms);
        }

        ModeRequestDTO body(WorldState snapshot) {
            if (snapshot.home() == null) {
                throw new IllegalArgumentException("Home state is unavailable");
            }
            var body = new ModeRequestDTO(ModeType.AT_HOME, ModeState.fromBoolean(active));
            if (active) {
                Set<Integer> selected = new TreeSet<>();
                for (int id : rooms) {
                    var room = snapshot.rooms().get(id);
                    if (room == null || !"None".equals(room.zone())) {
                        throw new IllegalArgumentException(
                                "Selected room missing or zone membership changed; select rooms again");
                    }
                    selected.add(id);
                }
                if (livingZone) {
                    for (var room : snapshot.rooms().values()) {
                        if ("Living".equals(room.zone())) {
                            selected.add(room.id());
                        }
                    }
                    if (selected.size() == rooms.size()) {
                        throw new IllegalArgumentException("Selected Living zone has no rooms");
                    }
                }
                if (selected.isEmpty()) {
                    throw new IllegalArgumentException(
                            "Select at least one room or the Living zone before enabling Home");
                }
                for (int id : selected) {
                    var room = snapshot.rooms().get(id);
                    if (room == null || !"Schedule".equals(room.regulationType())) {
                        throw new IllegalArgumentException("Every selected room must use scheduled regulation");
                    }
                }
                body.rooms = List.copyOf(selected);
            }
            return body;
        }
    }

    void select(@Nullable Integer roomId, OnOffType command, WorldState snapshot) {
        if (roomId == null) {
            selectZone(command, snapshot);
            return;
        }
        var room = snapshot.rooms().get(roomId);
        if (room == null) {
            if (command == OnOffType.OFF) {
                rooms.remove(roomId);
                return;
            }
            throw new IllegalArgumentException("Room is unavailable");
        }
        if ("Living".equals(room.zone())) {
            selectZone(command, snapshot);
        } else if ("None".equals(room.zone())) {
            if (command == OnOffType.ON) {
                rooms.add(roomId);
            } else {
                rooms.remove(roomId);
            }
        } else {
            throw new IllegalArgumentException("Room zone is unavailable or unknown");
        }
    }

    private void selectZone(OnOffType command, WorldState snapshot) {
        livingZone = command == OnOffType.ON;
        // An explicit zone selection supersedes individual selections made before rooms joined the zone.
        rooms.removeIf(id -> {
            var room = snapshot.rooms().get(id);
            return room != null && "Living".equals(room.zone());
        });
    }

    State state(@Nullable Integer roomId, WorldState snapshot) {
        if (roomId == null) {
            return livingZone ? OnOffType.ON : OnOffType.OFF;
        }
        var room = snapshot.rooms().get(roomId);
        if (room == null) {
            return UnDefType.UNDEF;
        }
        if ("Living".equals(room.zone())) {
            return livingZone ? OnOffType.ON : OnOffType.OFF;
        }
        return "None".equals(room.zone()) ? rooms.contains(roomId) ? OnOffType.ON : OnOffType.OFF : UnDefType.UNDEF;
    }

    Request request(OnOffType command) {
        return new Request(command == OnOffType.ON, rooms, livingZone);
    }

    static State reported(WorldState.@Nullable Mode home) {
        return home == null ? UnDefType.UNDEF : home.state() == ModeState.ACTIVE ? OnOffType.ON : OnOffType.OFF;
    }
}
