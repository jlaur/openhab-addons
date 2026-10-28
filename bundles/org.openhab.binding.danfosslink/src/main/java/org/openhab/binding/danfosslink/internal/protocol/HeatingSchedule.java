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
package org.openhab.binding.danfosslink.internal.protocol;

import static org.openhab.binding.danfosslink.internal.ProtocolJson.GSON;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.danfosslink.internal.protocol.dto.SchedulePeriodDTO;
import org.openhab.binding.danfosslink.internal.protocol.dto.ScheduleRequestDTO;
import org.openhab.binding.danfosslink.internal.protocol.dto.ScheduleState;
import org.openhab.binding.danfosslink.internal.protocol.dto.WeeklyScheduleDTO;

import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

/**
 * Prepares independent schedule patches, sorting periods and filling gaps with Away as the app does.
 *
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
public final class HeatingSchedule {
    private static final Set<String> DAYS = Set.of("monday", "tuesday", "wednesday", "thursday", "friday", "saturday",
            "sunday");

    /** Parses a user edit, rejecting unknown fields rather than silently discarding misspelled edits. */
    public static WeeklyScheduleDTO parse(String json) {
        try {
            var value = JsonParser.parseString(json);
            if (!value.isJsonObject()) {
                throw new IllegalArgumentException("Schedule must be a weekday object");
            }
            var object = value.getAsJsonObject();
            for (var entry : object.entrySet()) {
                if (!DAYS.contains(entry.getKey()) || !entry.getValue().isJsonArray()) {
                    throw new IllegalArgumentException("Unknown weekday or invalid day array: " + entry.getKey());
                }
                for (var period : entry.getValue().getAsJsonArray()) {
                    if (!period.isJsonObject() || !Set.of("state", "startTimeFromMidnight", "endTimeFromMidnight")
                            .containsAll(period.getAsJsonObject().keySet())) {
                        throw new IllegalArgumentException("Invalid schedule period fields");
                    }
                }
            }
            return normalize(Objects.requireNonNull(GSON.fromJson(object, WeeklyScheduleDTO.class)));
        } catch (JsonParseException e) {
            throw new IllegalArgumentException("Malformed schedule JSON", e);
        }
    }

    /** Combines weekday replacements, with later edits winning, without mutating either input. */
    public static String merge(String earlier, String later) {
        var result = GSON.toJsonTree(parse(earlier)).getAsJsonObject();
        GSON.toJsonTree(parse(later)).getAsJsonObject().entrySet()
                .forEach(entry -> result.add(entry.getKey(), entry.getValue()));
        return result.toString();
    }

    private HeatingSchedule() {
    }

    /** Validates a reported schedule without filling gaps or inventing omitted days. */
    public static String display(WeeklyScheduleDTO schedule) {
        normalize(schedule);
        return GSON.toJson(schedule);
    }

    public static ScheduleRequestDTO room(int roomId, WeeklyScheduleDTO schedule) {
        if (roomId < 0) {
            throw new IllegalArgumentException("Negative room ID");
        }
        var request = new ScheduleRequestDTO();
        request.id = roomId;
        request.schedule = normalize(schedule);
        return request;
    }

    public static ScheduleRequestDTO livingZone(WeeklyScheduleDTO schedule) {
        var request = new ScheduleRequestDTO();
        request.name = "Living";
        request.schedule = normalize(schedule);
        return request;
    }

    private static WeeklyScheduleDTO normalize(WeeklyScheduleDTO source) {
        var result = new WeeklyScheduleDTO();
        result.monday = day(source.monday);
        result.tuesday = day(source.tuesday);
        result.wednesday = day(source.wednesday);
        result.thursday = day(source.thursday);
        result.friday = day(source.friday);
        result.saturday = day(source.saturday);
        result.sunday = day(source.sunday);
        if (result.monday == null && result.tuesday == null && result.wednesday == null && result.thursday == null
                && result.friday == null && result.saturday == null && result.sunday == null) {
            throw new IllegalArgumentException("At least one schedule day is required");
        }
        return result;
    }

    private record Period(ScheduleState state, int start, int end) {
    }

    private static @Nullable List<@Nullable SchedulePeriodDTO> day(@Nullable List<@Nullable SchedulePeriodDTO> source) {
        if (source == null) {
            return null;
        }
        var periods = new ArrayList<Period>();
        for (@Nullable
        SchedulePeriodDTO period : source) {
            if (period == null) {
                throw new IllegalArgumentException("Missing schedule period");
            }
            ScheduleState state = period.state;
            LocalTime start = period.startTimeFromMidnight;
            LocalTime end = period.endTimeFromMidnight;
            if (state == null || start == null || end == null) {
                throw new IllegalArgumentException("Incomplete or unknown schedule period");
            }
            if (start.getSecond() != 0 || start.getNano() != 0 || end.getSecond() != 0 || end.getNano() != 0) {
                throw new IllegalArgumentException("Schedule periods require whole minutes");
            }
            int startMinute = start.toSecondOfDay() / 60;
            int endMinute = end.equals(LocalTime.MIDNIGHT) ? 1440 : end.toSecondOfDay() / 60;
            if (startMinute >= endMinute) {
                throw new IllegalArgumentException("Schedule periods must end after they start within the same day");
            }
            periods.add(new Period(state, startMinute, endMinute));
        }
        periods.sort(Comparator.comparingInt(Period::start));
        var result = new ArrayList<@Nullable SchedulePeriodDTO>();
        int cursor = 0;
        for (Period period : periods) {
            if (period.start() < cursor) {
                throw new IllegalArgumentException("Schedule periods overlap");
            }
            if (period.start() > cursor) {
                result.add(period(ScheduleState.AWAY, cursor, period.start()));
            }
            result.add(period(period.state(), period.start(), period.end()));
            cursor = period.end();
        }
        if (cursor < 1440) {
            result.add(period(ScheduleState.AWAY, cursor, 1440));
        }
        return result;
    }

    private static SchedulePeriodDTO period(ScheduleState state, int start, int end) {
        var period = new SchedulePeriodDTO();
        period.state = state;
        period.startTimeFromMidnight = LocalTime.ofSecondOfDay(start * 60L);
        period.endTimeFromMidnight = end == 1440 ? LocalTime.MIDNIGHT : LocalTime.ofSecondOfDay(end * 60L);
        return period;
    }
}
