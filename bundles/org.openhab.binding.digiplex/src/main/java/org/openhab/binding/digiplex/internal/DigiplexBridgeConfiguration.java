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
package org.openhab.binding.digiplex.internal;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * The {@link DigiplexBridgeConfiguration} class contains fields mapping bridge configuration parameters.
 *
 * @author Robert Michalak - Initial contribution
 */
@NonNullByDefault
public class DigiplexBridgeConfiguration {

    public String port = "";
    public int baudrate = 2400;
}
