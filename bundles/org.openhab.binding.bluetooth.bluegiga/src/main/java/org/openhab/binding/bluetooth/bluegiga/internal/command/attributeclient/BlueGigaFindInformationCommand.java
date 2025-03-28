/*
 * Copyright (c) 2010-2025 Contributors to the openHAB project
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
package org.openhab.binding.bluetooth.bluegiga.internal.command.attributeclient;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.bluetooth.bluegiga.internal.BlueGigaDeviceCommand;

/**
 * Class to implement the BlueGiga command <b>findInformation</b>.
 * <p>
 * This command can be used to find specific attributes on a remote device based on their 16-bit
 * UUID value and value. The search can be limited by a starting and ending handle values.
 * <p>
 * This class provides methods for processing BlueGiga API commands.
 * <p>
 * Note that this code is autogenerated. Manual changes may be overwritten.
 *
 * @author Chris Jackson - Initial contribution of Java code generator
 * @author Pauli Anttila - Added message builder
 */
@NonNullByDefault
public class BlueGigaFindInformationCommand extends BlueGigaDeviceCommand {
    public static final int COMMAND_CLASS = 0x04;
    public static final int COMMAND_METHOD = 0x03;

    private BlueGigaFindInformationCommand(CommandBuilder builder) {
        super.setConnection(builder.connection);
        this.start = builder.start;
        this.end = builder.end;
    }

    /**
     * First attribute handle
     * <p>
     * BlueGiga API type is <i>uint16</i> - Java type is {@link int}
     */
    private int start;

    /**
     * Last attribute handle
     * <p>
     * BlueGiga API type is <i>uint16</i> - Java type is {@link int}
     */
    private int end;

    @Override
    public int[] serialize() {
        // Serialize the header
        serializeHeader(COMMAND_CLASS, COMMAND_METHOD);

        // Serialize the fields
        serializeUInt8(connection);
        serializeUInt16(start);
        serializeUInt16(end);

        return getPayload();
    }

    @Override
    public String toString() {
        final StringBuilder builder = new StringBuilder();
        builder.append("BlueGigaFindInformationCommand [connection=");
        builder.append(connection);
        builder.append(", start=");
        builder.append(start);
        builder.append(", end=");
        builder.append(end);
        builder.append(']');
        return builder.toString();
    }

    public static class CommandBuilder {
        private int connection;
        private int start;
        private int end;

        /**
         * Set connection handle.
         *
         * @param connection the connection to set as {@link int}
         */
        public CommandBuilder withConnection(int connection) {
            this.connection = connection;
            return this;
        }

        /**
         * First requested handle number
         *
         * @param start the start to set as {@link int}
         */
        public CommandBuilder withStart(int start) {
            this.start = start;
            return this;
        }

        /**
         * Last requested handle number
         *
         * @param end the end to set as {@link int}
         */
        public CommandBuilder withEnd(int end) {
            this.end = end;
            return this;
        }

        public BlueGigaFindInformationCommand build() {
            return new BlueGigaFindInformationCommand(this);
        }
    }
}
