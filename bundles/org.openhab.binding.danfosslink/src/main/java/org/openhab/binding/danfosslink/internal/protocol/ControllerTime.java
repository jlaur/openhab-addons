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
package org.openhab.binding.danfosslink.internal.protocol;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.danfosslink.internal.WorldState.Mode;

/**
 * Converts deadlines using the controller clock observed in one world response.
 *
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
public record ControllerTime(Duration skew) {
    public static final ControllerTime UNADJUSTED = new ControllerTime(Duration.ZERO);

    public static ControllerTime observed(@Nullable ZonedDateTime controllerTime, Instant receivedAt) {
        return controllerTime == null ? UNADJUSTED
                : new ControllerTime(Duration.between(receivedAt, controllerTime.toInstant()));
    }

    public Instant toController(Instant value) {
        return value.plus(skew);
    }

    public Instant toOpenHab(Instant value) {
        return value.minus(skew);
    }

    public @Nullable Mode toOpenHab(@Nullable Mode mode) {
        if (mode == null) {
            return null;
        }
        Instant start = mode.start();
        Instant end = mode.end();
        return new Mode(mode.state(), start == null ? null : toOpenHab(start), end == null ? null : toOpenHab(end),
                mode.localOffset());
    }
}
