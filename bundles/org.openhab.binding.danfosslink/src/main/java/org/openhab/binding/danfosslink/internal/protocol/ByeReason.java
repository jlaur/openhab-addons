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

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * MDG control protocol session termination reasons.
 *
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
enum ByeReason {
    NO_ERROR(0),
    INTERNAL_ERROR(1),
    SYNTAX_ERROR(2),
    DENIED(3),
    TEMPORARILY_DENIED(4),
    PROTOCOL_ERROR(5),
    TIMEOUT(6),
    UNSUPPORTED_VERSION(7),
    UNKNOWN(-1);

    private final int code;

    ByeReason(int code) {
        this.code = code;
    }

    static ByeReason fromCode(long code) {
        for (var value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        return UNKNOWN;
    }
}
