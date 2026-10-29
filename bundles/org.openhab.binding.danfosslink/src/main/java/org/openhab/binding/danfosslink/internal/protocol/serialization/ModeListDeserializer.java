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

import java.lang.reflect.Type;
import java.time.DateTimeException;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.danfosslink.internal.protocol.dto.ModeDTO;
import org.openhab.binding.danfosslink.internal.protocol.dto.ModeType;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;

/**
 * Isolates malformed optional modes while retaining their type for validation.
 *
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
public final class ModeListDeserializer implements JsonDeserializer<List<ModeDTO>> {
    @Override
    public @Nullable List<ModeDTO> deserialize(JsonElement json, Type type, JsonDeserializationContext context) {
        if (!json.isJsonArray()) {
            return null;
        }
        List<ModeDTO> modes = new ArrayList<>();
        for (JsonElement element : json.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                return null;
            }
            var object = element.getAsJsonObject();
            ModeType modeType;
            try {
                modeType = context.deserialize(object.get("type"), ModeType.class);
            } catch (JsonParseException | IllegalStateException e) {
                return null;
            }
            if (modeType == null) {
                continue;
            }
            try {
                ModeDTO mode = context.deserialize(object, ModeDTO.class);
                if (mode != null) {
                    modes.add(mode);
                }
            } catch (JsonParseException | DateTimeException | IllegalStateException e) {
                // Do not discard a malformed known type: a second valid entry must not hide it.
                modes.add(new ModeDTO(modeType, null, null, null, null, null));
            }
        }
        return List.copyOf(modes);
    }
}
