/*
 * Copyright (c) 2010-2025 Contributors to the openHAB project
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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openhab.core.util.HexUtils;

/**
 * Tests for {@link CRC16Calculator}.
 *
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
@ExtendWith(MockitoExtension.class)
public class CRC16CalculatorTest {
    @Test
    void checkIsTrueForValidResponse() {
        byte[] response = HexUtils.hexToBytes(
                "2430F8E70A2C000100010000254357878B439781803D21B00040F19C0040EA4A404536FDB4FFC00000421C000042040000017317");
        assertThat(CRC16Calculator.check(response), is(true));
    }

    @Test
    void checkIsFalseForInvalidResponse() {
        byte[] response = HexUtils.hexToBytes(
                "2430F8E70A2C000100010000254357878B439781803D21B00040F19C0040EA4A404536FDB4FFC00000421C000042040000017318");
        assertThat(CRC16Calculator.check(response), is(false));
    }

    @Test
    void checkThrowsWhenResponseTooShort() {
        byte[] response = HexUtils.hexToBytes("2430");
        assertThrows(IllegalArgumentException.class, () -> {
            CRC16Calculator.check(response);
        });
    }

    @Test
    void checkThrowsWhenInvalidDataLength() {
        byte[] response = HexUtils.hexToBytes(
                "2430F8E70A2C000100010000254357878B439781803D21B00040F19C0040EA4A404536FDB4FFC00000421C0000420400000173");
        assertThrows(IllegalArgumentException.class, () -> {
            CRC16Calculator.check(response);
        });
    }
}