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
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

/**
 * Maps the optional controller timestamp without making malformed clock data prevent room polling.
 *
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
public class ControllerTimeAdapter extends TypeAdapter<ZonedDateTime> {
    @Override
    public @Nullable ZonedDateTime read(JsonReader reader) throws IOException {
        if (reader.peek() != JsonToken.STRING) {
            reader.skipValue();
            return null;
        }
        try {
            return ZonedDateTime.parse(reader.nextString());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    @Override
    public void write(JsonWriter writer, @Nullable ZonedDateTime value) throws IOException {
        if (value == null) {
            writer.nullValue();
        } else {
            writer.value(DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(value));
        }
    }
}
