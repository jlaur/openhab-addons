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

import java.time.LocalTime;

import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.danfosslink.internal.protocol.serialization.LocalTimeTypeAdapter;

import com.google.gson.annotations.JsonAdapter;

/**
 * One daily heating period. Midnight as an end time means the end of the day.
 *
 * @author Jacob Laursen - Initial contribution
 */
public class SchedulePeriodDTO {
    public @Nullable ScheduleState state;
    public @Nullable LocalTime startTimeFromMidnight;
    @JsonAdapter(LocalTimeTypeAdapter.EndOfDay.class)
    public @Nullable LocalTime endTimeFromMidnight;
}
