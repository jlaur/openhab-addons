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
final class DanfossLinkBindingConstants {
    static final String BINDING_ID = "danfosslink";
    static final ThingTypeUID CONTROLLER = new ThingTypeUID(BINDING_ID, "controller");
    static final ThingTypeUID ROOM = new ThingTypeUID(BINDING_ID, "room");
    static final String TEMPERATURE = "temperature";
    static final String SETPOINT = "setpoint";
    static final String REGULATION_STATE = "regulation-state";
    static final String ROOM_ID = "roomId";

    private DanfossLinkBindingConstants() {
    }
}
