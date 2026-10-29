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
package org.openhab.binding.danfosslink.internal.protocol.dto;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.eclipse.jdt.annotation.Nullable;

/**
 * Mode response payload, preserving separate local and legacy UTC timestamps.
 *
 * @author Jacob Laursen - Initial contribution
 */
public record ModeDTO(@Nullable ModeType type, @Nullable ModeState state, @Nullable LocalDateTime startTime,
        @Nullable LocalDateTime endTime, @Nullable Instant startTimeUtc, @Nullable Instant endTimeUtc) {

    /** A null local zone selects the UTC fields, without falling back to local fields. */
    public @Nullable Instant getStartTime(@Nullable ZoneId localZone) {
        return localZone == null ? validTime(startTimeUtc) : localTime(startTime, localZone);
    }

    public @Nullable Instant getEndTime(@Nullable ZoneId localZone) {
        return localZone == null ? validTime(endTimeUtc) : localTime(endTime, localZone);
    }

    private static @Nullable Instant localTime(@Nullable LocalDateTime value, ZoneId zone) {
        return value == null || value.getYear() < 1970 || value.getYear() > 2099 ? null
                : value.atZone(zone).toInstant();
    }

    private static @Nullable Instant validTime(@Nullable Instant value) {
        // The app treats dates outside this range as unspecified boundaries.
        return value == null ? null : localTime(LocalDateTime.ofInstant(value, ZoneOffset.UTC), ZoneOffset.UTC);
    }
}
