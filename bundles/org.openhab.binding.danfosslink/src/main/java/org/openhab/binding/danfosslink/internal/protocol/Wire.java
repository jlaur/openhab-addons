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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Bounded MDG framing and the protobuf wire types used by the control and
 * pairing protocols.
 *
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
public final class Wire {
    private Wire() {
    }

    public static byte[] read(InputStream input) throws IOException {
        // The caller owns the stream; closing this wrapper would close the connection.
        @SuppressWarnings("resource")
        var data = new DataInputStream(input);
        int length = data.readUnsignedShort();
        if (length == 0) {
            throw new IOException("Empty MDG frame");
        }
        byte[] result = input.readNBytes(length);
        if (result.length != length) {
            throw new EOFException("Truncated MDG frame");
        }
        return result;
    }

    public static void write(OutputStream output, byte[] packet) throws IOException {
        if (packet.length == 0 || packet.length > 65535) {
            throw new IOException("Invalid MDG frame length");
        }
        // The caller owns the stream; closing this wrapper would close the connection.
        @SuppressWarnings("resource")
        var data = new DataOutputStream(output);
        data.writeShort(packet.length);
        output.write(packet);
        output.flush();
    }

    public static Builder message(int type) {
        return new Builder().raw(new byte[] { (byte) type });
    }

    static Builder fields() {
        return new Builder();
    }

    public static Message decode(byte[] packet) throws IOException {
        if (packet.length == 0) {
            throw new IOException("Missing message type");
        }
        return new Message(packet[0] & 255, decodeFields(java.util.Arrays.copyOfRange(packet, 1, packet.length)));
    }

    public static Fields decodeFields(byte[] bytes) throws IOException {
        var input = new ByteArrayInputStream(bytes);
        Map<Integer, List<Object>> fields = new LinkedHashMap<>();
        while (input.available() > 0) {
            long tag = varint(input);
            if (tag < 0 || tag > 0xffffffffL) {
                throw new IOException("Invalid protobuf tag");
            }
            int field = (int) (tag >>> 3);
            if (field <= 0 || field > 0x1fffffff) {
                throw new IOException("Invalid protobuf field");
            }
            Object value = switch ((int) (tag & 7)) {
                case 0 -> varint(input);
                case 1 -> Long.reverseBytes(new DataInputStream(input).readLong());
                case 2 -> {
                    long length = varint(input);
                    if (length < 0 || length > input.available()) {
                        throw new IOException("Truncated protobuf value");
                    }
                    yield input.readNBytes((int) length);
                }
                case 5 -> Integer.toUnsignedLong(Integer.reverseBytes(new DataInputStream(input).readInt()));
                default -> throw new IOException("Unsupported protobuf wire type");
            };
            java.util.Objects.requireNonNull(fields.computeIfAbsent(field, ignored -> new ArrayList<>())).add(value);
        }
        return new Fields(fields);
    }

    private static long varint(InputStream input) throws IOException {
        long value = 0;
        for (int shift = 0; shift < 64; shift += 7) {
            int next = input.read();
            if (next < 0) {
                throw new EOFException("Truncated protobuf varint");
            }
            if (shift == 63 && (next & 254) != 0) {
                throw new IOException("Overflowing protobuf varint");
            }
            value |= (long) (next & 127) << shift;
            if ((next & 128) == 0) {
                return value;
            }
        }
        throw new IOException("Overlong protobuf varint");
    }

    public record Message(int type, Fields fields) {
    }

    public record Fields(Map<Integer, List<Object>> values) {
        public long number(int field) throws IOException {
            var list = values.get(field);
            if (list == null || !(list.getLast() instanceof Long value)) {
                throw new IOException("Missing numeric field " + field);
            }
            return value;
        }

        public byte[] bytes(int field) throws IOException {
            var list = values.get(field);
            if (list == null || !(list.getLast() instanceof byte[] value)) {
                throw new IOException("Missing byte field " + field);
            }
            return value;
        }

        public List<byte[]> repeatedBytes(int field) throws IOException {
            List<byte[]> result = new ArrayList<>();
            for (Object value : values.getOrDefault(field, List.of())) {
                if (!(value instanceof byte[] bytes)) {
                    throw new IOException("Wrong field type");
                }
                result.add(bytes);
            }
            return result;
        }

        public String text(int field) throws IOException {
            return new String(bytes(field), StandardCharsets.UTF_8);
        }
    }

    public static final class Builder {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();

        Builder raw(byte[] bytes) {
            output.writeBytes(bytes);
            return this;
        }

        private Builder varint(long number) {
            do {
                int low = (int) (number & 127);
                number >>>= 7;
                output.write(number == 0 ? low : low | 128);
            } while (number != 0);
            return this;
        }

        public Builder number(int field, long number) {
            return varint((long) field << 3).varint(number);
        }

        public Builder fixed32(int field, int number) {
            varint(((long) field << 3) | 5);
            for (int shift = 0; shift < 32; shift += 8) {
                output.write(number >>> shift);
            }
            return this;
        }

        public Builder bytes(int field, byte[] bytes) {
            return varint(((long) field << 3) | 2).varint(bytes.length).raw(bytes);
        }

        public Builder text(int field, String text) {
            return bytes(field, text.getBytes(StandardCharsets.UTF_8));
        }

        public byte[] build() {
            return output.toByteArray();
        }
    }
}
