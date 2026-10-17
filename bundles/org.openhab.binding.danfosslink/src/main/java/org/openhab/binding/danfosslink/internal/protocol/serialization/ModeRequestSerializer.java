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
package org.openhab.binding.danfosslink.internal.protocol.serialization;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.danfosslink.internal.protocol.dto.ModeRequestDTO;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

/**
 * Formats controller-time mode deadlines using the timestamp format of the current controller response.
 *
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
public final class ModeRequestSerializer {
    private ModeRequestSerializer() {
    }

    public static JsonObject serialize(ModeRequestDTO request, @Nullable ZoneOffset localOffset) {
        // Keep the timestamp format local to this request, including when multiple controllers are active.
        var gson = new GsonBuilder().registerTypeAdapter(Instant.class, new TypeAdapter<Instant>() {
            @Override
            public void write(JsonWriter writer, @Nullable Instant value) throws IOException {
                if (value == null) {
                    writer.nullValue();
                } else {
                    writer.value(timestamp(value, localOffset));
                }
            }

            @Override
            public @Nullable Instant read(JsonReader reader) {
                throw new UnsupportedOperationException("Mode request timestamps are serialization-only");
            }
        }).create();
        return gson.toJsonTree(request).getAsJsonObject();
    }

    private static String timestamp(Instant value, @Nullable ZoneOffset localOffset) {
        var timestamp = value.truncatedTo(ChronoUnit.SECONDS)
                .atOffset(localOffset == null ? ZoneOffset.UTC : localOffset);
        // Out-of-range protocol dates represent unspecified boundaries, not finite deadlines.
        if (timestamp.getYear() < 1970 || timestamp.getYear() > 2099) {
            throw new IllegalArgumentException("Controller deadline must be between 1970 and 2099");
        }
        return localOffset == null ? timestamp.toInstant().toString()
                : DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(timestamp);
    }
}
