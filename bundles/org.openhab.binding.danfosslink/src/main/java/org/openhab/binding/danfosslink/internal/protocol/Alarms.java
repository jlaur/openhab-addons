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

import java.io.IOException;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.danfosslink.internal.protocol.dto.AlarmDTO;

import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;

/**
 * An immutable alarm collection retaining the controller's fields, including unknown message parameters.
 *
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
public record Alarms(int count, String json) {
    public static Alarms parse(@Nullable JsonElement content) throws IOException {
        if (content == null || !content.isJsonArray()) {
            throw new IOException("Missing or malformed alarm collection");
        }
        var array = content.getAsJsonArray();
        try {
            for (JsonElement element : array) {
                if (!element.isJsonObject()) {
                    throw new IOException("Malformed alarm entry");
                }
                AlarmDTO alarm = GSON.fromJson(element, AlarmDTO.class);
                if (alarm == null || alarm.id == null) {
                    throw new IOException("Missing alarm ID");
                }
            }
        } catch (JsonParseException e) {
            throw new IOException("Malformed alarm entry", e);
        }
        return new Alarms(array.size(), array.toString());
    }
}
