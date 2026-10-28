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
package org.openhab.binding.danfosslink.internal;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZonedDateTime;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.danfosslink.internal.protocol.serialization.ControllerTimeAdapter;
import org.openhab.binding.danfosslink.internal.protocol.serialization.InstantTypeAdapter;
import org.openhab.binding.danfosslink.internal.protocol.serialization.LocalDateTimeTypeAdapter;
import org.openhab.binding.danfosslink.internal.protocol.serialization.LocalTimeTypeAdapter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * Shared JSON mappings for controller payload types.
 *
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
public final class ProtocolJson {
    public static final Gson GSON = new GsonBuilder().registerTypeAdapter(Instant.class, new InstantTypeAdapter())
            .registerTypeAdapter(LocalTime.class, new LocalTimeTypeAdapter())
            .registerTypeAdapter(LocalDateTime.class, new LocalDateTimeTypeAdapter())
            .registerTypeAdapter(ZonedDateTime.class, new ControllerTimeAdapter()).create();

    private ProtocolJson() {
    }
}
