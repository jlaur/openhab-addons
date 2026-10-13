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

import java.nio.ByteBuffer;
import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Writable mode members of the Alpha3's class-10 settings objects.
 *
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
public enum ModeSetting {
    OperatingMode(GeniReadRequest.OperatingMode, 6, 1, Set.of(0, 1, 2, 3)),
    ControlMode(GeniReadRequest.ControlMode, 10, 2, Set.of(0, 1, 2, 13, 14, 15));

    private static final int OBJECT_TYPE = 0x012f;
    private static final int OBJECT_VERSION = 1;
    private static final int OBJECT_LENGTH = 7;
    private static final int WRITE_OPERATION = 2;
    private static final int WRITE_PAYLOAD_LENGTH = 3 + 6 + OBJECT_LENGTH;

    private final GeniReadRequest readRequest;
    private final int subId;
    private final int offset;
    private final Set<Integer> options;

    ModeSetting(GeniReadRequest readRequest, int subId, int offset, Set<Integer> options) {
        this.readRequest = readRequest;
        this.subId = subId;
        this.offset = offset;
        this.options = options;
    }

    public GeniReadRequest readRequest() {
        return readRequest;
    }

    public boolean accepts(int value) {
        return options.contains(value);
    }

    byte[] writeRequest(byte[] currentObject, int value) {
        if (currentObject.length != OBJECT_LENGTH || !accepts(value)) {
            throw new IllegalArgumentException("Invalid mode object or value");
        }
        // Preserve the other members when writing the complete object, including its six-byte header.
        byte[] data = currentObject.clone();
        data[offset] = (byte) value;
        int messageLength = 4 + WRITE_PAYLOAD_LENGTH;
        byte[] request = new byte[messageLength + 4];
        GeniFrameHeader.setRequestHeader(request, messageLength);
        ByteBuffer.wrap(request).position(GeniFrameHeader.LENGTH).put((byte) GeniReadRequest.DATA_CLASS)
                .put((byte) (WRITE_OPERATION << 6 | WRITE_PAYLOAD_LENGTH)).put((byte) 0x56).putShort((short) subId)
                .putShort((short) OBJECT_TYPE).put((byte) OBJECT_VERSION).put((byte) 0).putShort((short) OBJECT_LENGTH)
                .put(data);
        CRC16Calculator.put(request, messageLength);
        return request;
    }
}
