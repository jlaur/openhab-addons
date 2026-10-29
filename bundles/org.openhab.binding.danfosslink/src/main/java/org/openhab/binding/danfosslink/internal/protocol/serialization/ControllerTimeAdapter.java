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

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

/**
 * Preserves the clock timestamp spelling while ignoring non-string optional clock data.
 *
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
public class ControllerTimeAdapter extends TypeAdapter<String> {
    @Override
    public @Nullable String read(JsonReader reader) throws IOException {
        if (reader.peek() != JsonToken.STRING) {
            reader.skipValue();
            return null;
        }
        return reader.nextString();
    }

    @Override
    public void write(JsonWriter writer, @Nullable String value) throws IOException {
        if (value == null) {
            writer.nullValue();
        } else {
            writer.value(value);
        }
    }
}
