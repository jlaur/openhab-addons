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
import java.time.LocalDateTime;

import org.eclipse.jdt.annotation.Nullable;

/**
 * Mode response payload, preserving separate local and legacy UTC timestamps.
 *
 * @author Jacob Laursen - Initial contribution
 */
public class ModeDTO {
    public @Nullable ModeType type;
    public @Nullable ModeState state;
    public @Nullable LocalDateTime startTime;
    public @Nullable LocalDateTime endTime;
    public @Nullable Instant startTimeUtc;
    public @Nullable Instant endTimeUtc;
}
