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

import java.util.List;

import org.eclipse.jdt.annotation.Nullable;

/**
 * Controller weekday fields. Omitted days are unchanged in a schedule PATCH.
 *
 * @author Jacob Laursen - Initial contribution
 */
public class WeeklyScheduleDTO {
    public @Nullable List<@Nullable SchedulePeriodDTO> monday;
    public @Nullable List<@Nullable SchedulePeriodDTO> tuesday;
    public @Nullable List<@Nullable SchedulePeriodDTO> wednesday;
    public @Nullable List<@Nullable SchedulePeriodDTO> thursday;
    public @Nullable List<@Nullable SchedulePeriodDTO> friday;
    public @Nullable List<@Nullable SchedulePeriodDTO> saturday;
    public @Nullable List<@Nullable SchedulePeriodDTO> sunday;
}
