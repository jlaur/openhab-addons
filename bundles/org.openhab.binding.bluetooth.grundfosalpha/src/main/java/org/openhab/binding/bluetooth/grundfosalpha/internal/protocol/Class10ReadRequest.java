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

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Supported GENI class-10 read requests and their expected response object layouts.
 *
 * Requests select data using an 8-bit data ID and a 16-bit sub-ID.
 * Responses are decoded only when their object type, version and data length
 * match the supported layout.
 *
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
public enum Class10ReadRequest {
    FlowHead(0x5d, 0x0121, 0x0130, 1, 24),
    Power(0x57, 0x0045, 0x0100, 1, 37);

    static final int DATA_CLASS = 10;
    private static final int READ_PAYLOAD_LENGTH = 3;

    private final int objectType;
    private final int objectVersion;
    private final int objectLength;
    private final byte[] request;

    /**
     * @param dataId The 8-bit identifier of the requested data group
     * @param subId The 16-bit identifier of the object or block within the data group
     * @param objectType The 16-bit identifier of the expected response object layout
     * @param objectVersion The 8-bit version of the expected object layout
     * @param objectLength The expected object data length, excluding the six-byte object header
     */
    Class10ReadRequest(int dataId, int subId, int objectType, int objectVersion, int objectLength) {
        this.objectType = objectType;
        this.objectVersion = objectVersion;
        this.objectLength = objectLength;

        // Two address bytes and one APDU: class, operation/length, data ID and 16-bit sub-ID.
        int messageLength = 4 + READ_PAYLOAD_LENGTH;
        request = new byte[messageLength + 4];
        GeniFrameHeader.setRequestHeader(request, messageLength);
        // The upper two bits select read operation 0; the lower six bits specify three payload bytes.
        ByteBuffer.wrap(request).position(GeniFrameHeader.LENGTH).put((byte) DATA_CLASS).put((byte) READ_PAYLOAD_LENGTH)
                .put((byte) dataId).putShort((short) subId);

        CRC16Calculator.put(request, messageLength);
    }

    boolean matchesObject(int type, int version, int length) {
        return objectType == type && objectVersion == version && objectLength == length;
    }

    public byte[] request() {
        return request;
    }
}
