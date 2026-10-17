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
import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * Mode update payload. Unused fields are omitted by Gson.
 * Deadlines represent controller time.
 *
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
public class ModeRequestDTO {
    public final String type;
    public final String state;
    public @Nullable Instant startTime;
    public @Nullable Instant endTime;
    public @Nullable List<Integer> rooms;

    public ModeRequestDTO(String type, String state) {
        this.type = type;
        this.state = state;
    }
}
