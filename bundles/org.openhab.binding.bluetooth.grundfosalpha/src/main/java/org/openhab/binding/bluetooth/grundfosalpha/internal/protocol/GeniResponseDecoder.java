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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.util.HexUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Assembles a GENI reply from ordered Bluetooth notifications and decodes supported class-10 objects.
 * The first notification must contain the transport header. A new reply header discards an incomplete reply.
 *
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
public class GeniResponseDecoder {

    private final Logger logger = LoggerFactory.getLogger(GeniResponseDecoder.class);

    private static final int GENI_RESPONSE_MAX_SIZE = 259;
    private static final int CHECKSUM_LENGTH = 2;
    private static final int OBJECT_HEADER_LENGTH = 6;

    private int responseTotalSize;
    private int responseOffset;
    private boolean complete;
    private final byte[] response = new byte[GENI_RESPONSE_MAX_SIZE];

    /**
     * Add packet from response payload.
     *
     * @param packet A notification containing the start or continuation of a reply
     * @return true if a complete reply with a valid checksum has been received
     */
    public boolean addPacket(byte[] packet) {
        if (logger.isTraceEnabled()) {
            logger.trace("GENI response: {}", HexUtils.bytesToHex(packet));
        }

        if (packet.length == 0) {
            return false;
        }
        if (GeniFrameHeader.isInitialResponsePacket(packet)) {
            reset();
            responseTotalSize = GeniFrameHeader.getTotalSize(packet);
            if (responseTotalSize < GeniFrameHeader.LENGTH + CHECKSUM_LENGTH) {
                logger.debug("Invalid GENI reply length: {}", responseTotalSize);
                reset();
                return false;
            }
        } else if (responseTotalSize == 0 || complete) {
            reset();
            if (logger.isDebugEnabled()) {
                logger.debug("Response bytes {} don't match GENI header", HexUtils.bytesToHex(packet));
            }
            return false;
        }

        if (packet.length > responseTotalSize - responseOffset) {
            reset();
            logger.debug("Received too many bytes");
            return false;
        }

        System.arraycopy(packet, 0, response, responseOffset, packet.length);
        responseOffset += packet.length;

        if (responseOffset == responseTotalSize) {
            if (!CRC16Calculator.check(response)) {
                if (logger.isDebugEnabled()) {
                    logger.debug("CRC16 check failed for {}",
                            HexUtils.bytesToHex(Arrays.copyOf(response, responseTotalSize)));
                }
                reset();
                return false;
            }
            complete = true;
            return true;
        }

        return false;
    }

    public Map<SensorDataType, BigDecimal> decode() {
        Map<SensorDataType, BigDecimal> values = new EnumMap<>(SensorDataType.class);
        if (!complete) {
            return values;
        }

        ByteBuffer apdus = ByteBuffer
                .wrap(response, GeniFrameHeader.LENGTH, responseTotalSize - GeniFrameHeader.LENGTH - CHECKSUM_LENGTH)
                .slice().order(ByteOrder.BIG_ENDIAN);
        while (apdus.hasRemaining()) {
            int dataClass = Byte.toUnsignedInt(apdus.get());
            // GENI permits single-byte extension fields between APDUs (GO Remote's telegram parser).
            if ((dataClass & 0x80) != 0) {
                continue;
            }
            if (!apdus.hasRemaining()) {
                logger.debug("Truncated GENI application header");
                return Map.of();
            }
            int acknowledgementAndLength = Byte.toUnsignedInt(apdus.get());
            int acknowledgement = acknowledgementAndLength >>> 6;
            int payloadLength = acknowledgementAndLength & 0x3f;
            if (payloadLength > apdus.remaining()) {
                logger.debug("GENI application payload exceeds reply length");
                return Map.of();
            }
            ByteBuffer payload = apdus.slice().order(ByteOrder.BIG_ENDIAN);
            payload.limit(payloadLength);
            apdus.position(apdus.position() + payloadLength);
            if (acknowledgement != 0) {
                logger.debug("Ignoring GENI class {} reply with acknowledgement {}", dataClass, acknowledgement);
            } else if (dataClass == Class10ReadRequest.DATA_CLASS && !decodeClass10(payload, values)) {
                return Map.of();
            }
        }

        return values;
    }

    private boolean decodeClass10(ByteBuffer payload, Map<SensorDataType, BigDecimal> values) {
        if (!payload.hasRemaining()) {
            logger.debug("Missing GENI class-10 object status");
            return false;
        }
        int objectStatus = Byte.toUnsignedInt(payload.get());
        if (objectStatus != 0) {
            logger.debug("Ignoring GENI class-10 object with status {}", objectStatus);
            return true;
        }
        if (payload.remaining() < OBJECT_HEADER_LENGTH) {
            logger.debug("Truncated GENI class-10 object header");
            return false;
        }

        int objectType = Short.toUnsignedInt(payload.getShort());
        int objectVersion = Byte.toUnsignedInt(payload.get());
        int objectLength = (Byte.toUnsignedInt(payload.get()) << 16) | (Byte.toUnsignedInt(payload.get()) << 8)
                | Byte.toUnsignedInt(payload.get());
        // The supported telemetry objects fit in one APDU. Do not decode partial objects or CRC bytes as sensor data.
        if (objectLength != payload.remaining()) {
            logger.debug("GENI class-10 object length {} does not match payload length {}", objectLength,
                    payload.remaining());
            return false;
        }

        int dataOffset = payload.position();
        for (SensorDataType dataType : SensorDataType.values()) {
            if (dataType.readRequest().matchesObject(objectType, objectVersion, objectLength)
                    && dataType.offset() <= objectLength - Float.BYTES) {
                float value = payload.getFloat(dataOffset + dataType.offset());
                if (Float.isFinite(value)) {
                    values.put(dataType, new BigDecimal(value).multiply(dataType.factor()).setScale(dataType.decimals(),
                            RoundingMode.HALF_UP));
                }
            }
        }
        return true;
    }

    private void reset() {
        responseTotalSize = 0;
        responseOffset = 0;
        complete = false;
    }
}
