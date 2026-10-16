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

import java.math.BigDecimal;

import org.eclipse.jdt.annotation.Nullable;

/**
 * Room temperatures, regulation settings, and zone membership.
 *
 * @author Jacob Laursen - Initial contribution
 */
public class RoomDTO {
    // Keep the original numeric value so fractional and overflowing identifiers can be rejected.
    public @Nullable BigDecimal id;
    public @Nullable String name;
    public @Nullable Boolean hasTemperatureSensor;
    public @Nullable BigDecimal ambientTemperature;
    public @Nullable BigDecimal minSetpoint;
    public @Nullable BigDecimal maxSetpoint;
    public @Nullable String regulationState;
    public @Nullable String zone;
    public @Nullable SetpointDTO setpoint;
    public @Nullable RegulationDTO regulation;

    public static class SetpointDTO {
        public @Nullable BigDecimal temperature;
    }

    public static class RegulationDTO {
        public @Nullable String type;
        public @Nullable BigDecimal atHomeSetpoint;
        public @Nullable BigDecimal awaySetpoint;
        public @Nullable BigDecimal manualSetpoint;
    }
}
