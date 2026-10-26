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
 * MDG routing protocol error codes, independent of control call results.
 *
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
enum RelayError {
    NO_ERROR(0),
    SERVER_ERROR(1),
    SYNTAX_ERROR(2),
    INVALID_TOKEN(3),
    PEER_TIMEOUT(4),
    UNSUPPORTED_VERSION(5),
    UNKNOWN(-1);

    private final int code;

    RelayError(int code) {
        this.code = code;
    }

    static RelayError fromCode(long code) {
        for (var value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        return UNKNOWN;
    }
}
