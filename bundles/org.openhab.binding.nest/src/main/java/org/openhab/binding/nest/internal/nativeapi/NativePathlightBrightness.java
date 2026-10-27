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
package org.openhab.binding.nest.internal.nativeapi;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * Discrete brightness levels used by the Protect app.
 *
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
enum NativePathlightBrightness {
    LOW(1),
    MEDIUM(2),
    HIGH(3);

    final int value;

    NativePathlightBrightness(int value) {
        this.value = value;
    }

    static @Nullable NativePathlightBrightness observed(@Nullable Integer value) {
        if (value == null) {
            return null;
        }
        return switch (value) {
            case 1 -> LOW;
            case 2 -> MEDIUM;
            case 3 -> HIGH;
            default -> null;
        };
    }
}
