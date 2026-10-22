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

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

/**
 * UTC timestamps, without interpreting controller sentinel values or clock skew.
 *
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
public final class InstantTypeAdapter extends TypeAdapter<Instant> {
    @Override
    public @Nullable Instant read(JsonReader reader) throws IOException {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull();
            return null;
        }
        return Instant.parse(reader.nextString());
    }

    @Override
    public void write(JsonWriter writer, @Nullable Instant value) throws IOException {
        if (value == null) {
            writer.nullValue();
        } else {
            writer.value(value.toString());
        }
    }
}
