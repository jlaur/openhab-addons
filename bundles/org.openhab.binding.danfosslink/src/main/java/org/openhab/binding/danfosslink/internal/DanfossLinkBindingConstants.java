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
package org.openhab.binding.danfosslink.internal;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.thing.ThingTypeUID;

/**
 * Thing and channel identifiers.
 *
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
public final class DanfossLinkBindingConstants {
    public static final String BINDING_ID = "danfosslink";

    public static final ThingTypeUID THING_TYPE_CONTROLLER = new ThingTypeUID(BINDING_ID, "controller");
    public static final ThingTypeUID THING_TYPE_LIVING_ZONE = new ThingTypeUID(BINDING_ID, "living-zone");
    public static final ThingTypeUID THING_TYPE_ROOM = new ThingTypeUID(BINDING_ID, "room");

    public static final String CHANNEL_TEMPERATURE = "temperature";
    public static final String CHANNEL_SETPOINT = "setpoint";
    public static final String CHANNEL_HOME_SETPOINT = "home-setpoint";
    public static final String CHANNEL_AWAY_SETPOINT = "away-setpoint";
    public static final String CHANNEL_MANUAL_SETPOINT = "manual-setpoint";
    public static final String CHANNEL_MANUAL = "manual";
    public static final String CHANNEL_LIVING_ZONE = "living-zone";
    public static final String CHANNEL_REGULATION_STATE = "regulation-state";

    public static final String PARAMETER_ROOM_ID = "roomId";

    /** App name shown on the Link CC Controller after pairing */
    public static final String APP_NAME = "openHAB";
}
