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
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

import com.google.gson.JsonParseException;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

/**
 * Schedule wall-clock times; an end-of-day boundary is represented by midnight.
 *
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
public class LocalTimeTypeAdapter extends TypeAdapter<LocalTime> {
    private final boolean endOfDay;

    public LocalTimeTypeAdapter() {
        this(false);
    }

    private LocalTimeTypeAdapter(boolean endOfDay) {
        this.endOfDay = endOfDay;
    }

    @Override
    public @Nullable LocalTime read(JsonReader reader) throws IOException {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull();
            return null;
        }
        if (reader.peek() != JsonToken.STRING) {
            throw new JsonParseException("Schedule time must be a string");
        }
        String value = reader.nextString();
        if (endOfDay && "1.00:00:00".equals(value)) {
            return LocalTime.MIDNIGHT;
        }
        try {
            return LocalTime.parse(value, DateTimeFormatter.ISO_LOCAL_TIME);
        } catch (DateTimeParseException e) {
            throw new JsonParseException("Invalid schedule time", e);
        }
    }

    @Override
    public void write(JsonWriter writer, @Nullable LocalTime value) throws IOException {
        if (value == null) {
            writer.nullValue();
        } else if (endOfDay && LocalTime.MIDNIGHT.equals(value)) {
            writer.value("1.00:00:00");
        } else {
            writer.value(DateTimeFormatter.ISO_LOCAL_TIME.format(value));
        }
    }

    /**
     * End times use the controller's next-midnight encoding instead of wrapping to the beginning of the day.
     */
    public static final class EndOfDay extends LocalTimeTypeAdapter {
        public EndOfDay() {
            super(true);
        }
    }
}
