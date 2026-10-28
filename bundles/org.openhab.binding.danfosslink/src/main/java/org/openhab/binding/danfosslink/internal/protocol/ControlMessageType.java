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
 * MDG control message types from mdg_control_pb.lua.
 *
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
enum ControlMessageType {
    HANDSHAKE(1),
    BYE(2),
    RECONNECT(3),
    PING(4),
    PING_REPLY(5),
    PLACE_CALL(10),
    PLACE_CALL_REPLY(11),
    INCOMING_CALL(12),
    INCOMING_CALL_REPLY(13),
    SET_PEER_WHITELIST(20),
    SET_PEER_WHITELIST_REPLY(21),
    ALLOCATE_PAIRING_CHANNEL(30),
    ALLOCATE_PAIRING_CHANNEL_REPLY(31),
    PLACE_PAIRING_CALL(32),
    PLACE_PAIRING_CALL_REPLY(33),
    UNKNOWN(-1);

    private final int code;

    ControlMessageType(int code) {
        this.code = code;
    }

    int code() {
        return code;
    }

    static ControlMessageType fromCode(long code) {
        for (var value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        return UNKNOWN;
    }
}
