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

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;

import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.danfosslink.internal.protocol.serialization.ControllerTimeAdapter;

import com.google.gson.annotations.JsonAdapter;

/**
 * Controller identification and clock information.
 *
 * @author Jacob Laursen - Initial contribution
 */
public record SystemInfoDTO(@Nullable Boolean autoUpdateSoftware, @Nullable String brandingType,
        @Nullable Boolean limitPauseDuration, @Nullable String name, @Nullable String serialNo,
        @JsonAdapter(ControllerTimeAdapter.class) @Nullable String systemTime, @Nullable String uptime,
        @Nullable String version) {

    public @Nullable ZonedDateTime getSystemTime() {
        String timestamp = systemTime;
        if (timestamp == null) {
            return null;
        }
        try {
            return ZonedDateTime.parse(timestamp);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** Numeric offsets select local mode timestamps; a literal Z selects UTC fields. */
    public @Nullable ZoneOffset getLocalTimeOffset() {
        String timestamp = systemTime;
        var time = getSystemTime();
        return time == null || timestamp == null || timestamp.endsWith("Z") ? null : time.getOffset();
    }
}
