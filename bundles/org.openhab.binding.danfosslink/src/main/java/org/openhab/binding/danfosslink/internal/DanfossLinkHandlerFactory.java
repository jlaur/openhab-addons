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

import static org.openhab.binding.danfosslink.internal.DanfossLinkBindingConstants.*;

import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.danfosslink.internal.handler.ControllerHandler;
import org.openhab.binding.danfosslink.internal.handler.LivingZoneHandler;
import org.openhab.binding.danfosslink.internal.handler.RoomHandler;
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

    private static final Set<ThingTypeUID> SUPPORTED_THING_TYPES_UIDS = Set.of(THING_TYPE_CONTROLLER, THING_TYPE_ROOM,
            THING_TYPE_LIVING_ZONE);

    private final PairingStore store;
    private final DanfossLinkStateDescriptionProvider descriptions;

    @Activate
    public DanfossLinkHandlerFactory(@Reference StorageService storageService,
            @Reference DanfossLinkStateDescriptionProvider descriptions) {
        this.descriptions = descriptions;
        store = new PairingStore(
                storageService.getStorage("org.openhab.binding.danfosslink.pairings", getClass().getClassLoader()));
    }

    @Override
    public boolean supportsThingType(ThingTypeUID type) {
        return SUPPORTED_THING_TYPES_UIDS.contains(type);
    }

    @Override
    protected @Nullable ThingHandler createHandler(Thing thing) {
        if (DanfossLinkBindingConstants.THING_TYPE_CONTROLLER.equals(thing.getThingTypeUID())
                && thing instanceof Bridge bridge) {
            return new ControllerHandler(bridge, store);
        }
        if (DanfossLinkBindingConstants.THING_TYPE_LIVING_ZONE.equals(thing.getThingTypeUID())) {
            return new LivingZoneHandler(thing);
        }
        return DanfossLinkBindingConstants.THING_TYPE_ROOM.equals(thing.getThingTypeUID())
                ? new RoomHandler(thing, descriptions)
                : null;
    }
}
