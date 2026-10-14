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
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.storage.StorageService;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.binding.BaseThingHandlerFactory;
import org.openhab.core.thing.binding.ThingHandler;
import org.openhab.core.thing.binding.ThingHandlerFactory;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

/**
 * Creates controller and room handlers with a lifecycle-bound storage dependency.
 *
 * @author Jacob Laursen - Initial contribution
 */
@Component(service = ThingHandlerFactory.class, configurationPid = "binding.danfosslink")
@NonNullByDefault
public class DanfossLinkHandlerFactory extends BaseThingHandlerFactory {
    private final PairingStore store;

    @Activate
    public DanfossLinkHandlerFactory(@Reference StorageService storageService) {
        store = new PairingStore(
                storageService.getStorage("org.openhab.binding.danfosslink.pairings", getClass().getClassLoader()));
    }

    @Override
    public boolean supportsThingType(ThingTypeUID type) {
        return DanfossLinkBindingConstants.CONTROLLER.equals(type) || DanfossLinkBindingConstants.ROOM.equals(type);
    }

    @Override
    protected @Nullable ThingHandler createHandler(Thing thing) {
        if (DanfossLinkBindingConstants.CONTROLLER.equals(thing.getThingTypeUID()) && thing instanceof Bridge bridge) {
            return new ControllerHandler(bridge, store);
        }
        return DanfossLinkBindingConstants.ROOM.equals(thing.getThingTypeUID()) ? new RoomHandler(thing) : null;
    }
}
