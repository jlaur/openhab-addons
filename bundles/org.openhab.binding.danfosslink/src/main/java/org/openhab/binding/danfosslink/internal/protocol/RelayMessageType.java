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
 * MDG relay message types from mdg_routing_protocol.lua.
 *
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
enum RelayMessageType {
    HANDSHAKE(0),
    PLEASE_HOLD(1),
    GO_AHEAD(2),
    ROUTING_ERROR(3),
    UNKNOWN(-1);

    private final int code;

    RelayMessageType(int code) {
        this.code = code;
    }

    int code() {
        return code;
    }

    static RelayMessageType fromCode(long code) {
        for (var value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        return UNKNOWN;
    }
}
