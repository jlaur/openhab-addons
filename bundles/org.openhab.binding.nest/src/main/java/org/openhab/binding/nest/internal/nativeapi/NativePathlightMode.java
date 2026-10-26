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
import org.openhab.binding.nest.internal.nativeapi.dto.NativeDtos.Protect;

/**
 * Pathlight modes encoded by the app as two independent night-light flags.
 *
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
enum NativePathlightMode {
    OFF,
    ON,
    ALWAYS_ON;

    static @Nullable NativePathlightMode observed(Protect protect) {
        if (Boolean.TRUE.equals(protect.nightLightContinuous)) {
            return ALWAYS_ON;
        }
        if (protect.nightLightContinuous == null || protect.nightLightEnabled == null) {
            return null;
        }
        return Boolean.TRUE.equals(protect.nightLightEnabled) ? ON : OFF;
    }

    boolean supported(Protect protect) {
        // PowerType in the app uses 0 for wired and 1 for battery; line power is only the current supply state.
        return this != ALWAYS_ON || Integer.valueOf(0).equals(protect.powerType);
    }
}
