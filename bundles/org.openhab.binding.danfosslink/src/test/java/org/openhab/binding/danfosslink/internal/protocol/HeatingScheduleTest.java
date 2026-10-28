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

import static org.junit.jupiter.api.Assertions.*;
import static org.openhab.binding.danfosslink.internal.ProtocolJson.GSON;

import java.time.LocalTime;
import java.util.Objects;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.binding.danfosslink.internal.protocol.dto.SchedulePeriodDTO;
import org.openhab.binding.danfosslink.internal.protocol.dto.WeeklyScheduleDTO;

import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

/**
 * Schedule encoding, complete-day normalization, and rejection of unsafe partial edits.
 *
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
class HeatingScheduleTest {
    private static WeeklyScheduleDTO schedule(String json) {
        return Objects.requireNonNull(GSON.fromJson(json, WeeklyScheduleDTO.class));
    }

    @Test
    void displayPreservesReportedGapsAndOmittedDaysButRejectsMalformedPeriods() {
        var json = JsonParser.parseString("""
                {"monday":[{"state":"AtHome","startTimeFromMidnight":"06:00:00",
                "endTimeFromMidnight":"08:00:00"}],"sunday":[]}
                """);
        assertEquals(json, JsonParser.parseString(HeatingSchedule.display(schedule(json.toString()))));
        assertThrows(IllegalArgumentException.class, () -> HeatingSchedule.display(schedule("{}")));
        assertThrows(IllegalArgumentException.class, () -> HeatingSchedule.display(schedule("""
                {"monday":[{"state":"Unknown","startTimeFromMidnight":"00:00:00",
                "endTimeFromMidnight":"1.00:00:00"}]}
                """)));
    }

    @Test
    void localTimesRoundTripIncludingEndOfDayAndSleeping() {
        var json = JsonParser.parseString("""
                {"state":"Asleep","startTimeFromMidnight":"00:00:00","endTimeFromMidnight":"1.00:00:00"}
                """);
        var period = Objects.requireNonNull(GSON.fromJson(json, SchedulePeriodDTO.class));
        assertEquals(LocalTime.MIDNIGHT, period.startTimeFromMidnight);
        assertEquals(LocalTime.MIDNIGHT, period.endTimeFromMidnight);
        assertEquals(json, GSON.toJsonTree(period));
        assertEquals("\"06:30:00\"", GSON.toJson(LocalTime.of(6, 30)));
        for (String invalid : new String[] { "1.00:00:00", "24:00:00", "06:60:00", "06:00:00Z", "-01:00:00" }) {
            assertThrows(JsonParseException.class, () -> GSON.fromJson("\"" + invalid + "\"", LocalTime.class));
        }
        assertThrows(JsonParseException.class, () -> GSON.fromJson("42", LocalTime.class));
        assertThrows(JsonParseException.class,
                () -> GSON.fromJson("{\"endTimeFromMidnight\":\"2.00:00:00\"}", SchedulePeriodDTO.class));
        assertNull(GSON.fromJson("null", LocalTime.class));
    }

    @Test
    void roomPatchSortsFillsGapsAndPreservesOmittedDaysWithoutMutatingInput() {
        var input = schedule("""
                {"monday":[
                {"state":"AtHome","startTimeFromMidnight":"18:00:00","endTimeFromMidnight":"22:00:00"},
                {"state":"AtHome","startTimeFromMidnight":"06:00:00","endTimeFromMidnight":"08:00:00"}]}
                """);
        var before = GSON.toJsonTree(input);
        var request = HeatingSchedule.room(2, input);
        assertEquals(JsonParser.parseString("""
                {"id":2,"schedule":{"monday":[
                {"state":"Away","startTimeFromMidnight":"00:00:00","endTimeFromMidnight":"06:00:00"},
                {"state":"AtHome","startTimeFromMidnight":"06:00:00","endTimeFromMidnight":"08:00:00"},
                {"state":"Away","startTimeFromMidnight":"08:00:00","endTimeFromMidnight":"18:00:00"},
                {"state":"AtHome","startTimeFromMidnight":"18:00:00","endTimeFromMidnight":"22:00:00"},
                {"state":"Away","startTimeFromMidnight":"22:00:00","endTimeFromMidnight":"1.00:00:00"}]}}
                """), GSON.toJsonTree(request));
        assertEquals(before, GSON.toJsonTree(input));
        Objects.requireNonNull(input.monday).clear();
        assertEquals(5, Objects.requireNonNull(Objects.requireNonNull(request.schedule).monday).size());
    }

    @Test
    void emptyDaysBecomeAllDayAwayAndAllSevenDaysAreSupported() {
        var input = schedule("""
                {"monday":[],"tuesday":[],"wednesday":[],"thursday":[],"friday":[],"saturday":[],"sunday":[]}
                """);
        var json = GSON.toJsonTree(HeatingSchedule.livingZone(input)).getAsJsonObject();
        assertEquals("Living", json.get("name").getAsString());
        assertFalse(json.has("id"));
        var days = json.getAsJsonObject("schedule");
        assertEquals(7, days.size());
        for (var day : days.entrySet()) {
            assertEquals(JsonParser.parseString("""
                    [{"state":"Away","startTimeFromMidnight":"00:00:00","endTimeFromMidnight":"1.00:00:00"}]
                    """), day.getValue());
        }
    }

    @Test
    void invalidSchedulesCannotBeWritten() {
        for (String day : new String[] { "[null]", "[{}]",
                "[{\"state\":\"Future\",\"startTimeFromMidnight\":\"06:00:00\",\"endTimeFromMidnight\":\"08:00:00\"}]",
                "[{\"state\":\"AtHome\",\"startTimeFromMidnight\":\"22:00:00\",\"endTimeFromMidnight\":\"06:00:00\"}]",
                "[{\"state\":\"AtHome\",\"startTimeFromMidnight\":\"06:00:00\",\"endTimeFromMidnight\":\"06:00:00\"}]",
                "[{\"state\":\"AtHome\",\"startTimeFromMidnight\":\"06:00:01\",\"endTimeFromMidnight\":\"08:00:00\"}]",
                "[{\"state\":\"AtHome\",\"startTimeFromMidnight\":\"06:00:00\",\"endTimeFromMidnight\":\"08:00:00.1\"}]",
                "[{\"state\":\"AtHome\",\"startTimeFromMidnight\":\"06:00:00\",\"endTimeFromMidnight\":\"08:00:00\"},"
                        + "{\"state\":\"Away\",\"startTimeFromMidnight\":\"07:00:00\",\"endTimeFromMidnight\":\"09:00:00\"}]" }) {
            var input = schedule("{\"monday\":" + day + "}");
            assertThrows(IllegalArgumentException.class, () -> HeatingSchedule.room(0, input), day);
        }
        assertThrows(IllegalArgumentException.class, () -> HeatingSchedule.room(-1, schedule("{\"monday\":[]}")));
        assertThrows(IllegalArgumentException.class, () -> HeatingSchedule.livingZone(schedule("{}")));
    }
}
