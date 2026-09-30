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
package org.openhab.binding.bluetooth.grundfosalpha.internal.protocol;

import java.math.BigDecimal;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * This represents the different sensor data types that can
 * be extracted from a {@link GeniResponseDecoder}.
 *
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
public enum SensorDataType {
    Flow(Class10ReadRequest.FlowHead, 0, new BigDecimal(3600), 3),
    Head(Class10ReadRequest.FlowHead, 4, new BigDecimal("0.0001"), 5),
    VoltageAC(Class10ReadRequest.Power, 0, BigDecimal.ONE, 1),
    PowerConsumption(Class10ReadRequest.Power, 12, BigDecimal.ONE, 1),
    MotorSpeed(Class10ReadRequest.Power, 20, BigDecimal.ONE, 0);

    private final Class10ReadRequest readRequest;
    private final int offset;
    private final BigDecimal factor;
    private final int decimals;

    SensorDataType(Class10ReadRequest readRequest, int offset, BigDecimal factor, int decimals) {
        this.readRequest = readRequest;
        this.offset = offset;
        this.factor = factor;
        this.decimals = decimals;
    }

    public Class10ReadRequest readRequest() {
        return readRequest;
    }

    public int offset() {
        return offset;
    }

    public BigDecimal factor() {
        return factor;
    }

    public int decimals() {
        return decimals;
    }
}
