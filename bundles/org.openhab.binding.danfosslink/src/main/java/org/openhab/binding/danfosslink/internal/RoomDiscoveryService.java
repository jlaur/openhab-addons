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

import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.config.discovery.AbstractDiscoveryService;
import org.openhab.core.config.discovery.DiscoveryResultBuilder;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.binding.ThingHandler;
import org.openhab.core.thing.binding.ThingHandlerService;

/**
 * Discovers stable room identifiers under their paired controller Bridge.
 *
 * @author Jacob Laursen - Initial contribution
 */
@org.osgi.service.component.annotations.Component(scope = org.osgi.service.component.annotations.ServiceScope.PROTOTYPE, service = RoomDiscoveryService.class)
@NonNullByDefault
public class RoomDiscoveryService extends AbstractDiscoveryService implements ThingHandlerService {
    private volatile @Nullable ControllerHandler handler;

    public RoomDiscoveryService() {
        super(Set.of(DanfossLinkBindingConstants.ROOM), 90, false);
    }

    @Override
    public void setThingHandler(ThingHandler handler) {
        if (handler instanceof ControllerHandler controller) {
            this.handler = controller;
            controller.discovery(this);
        }
    }

    @Override
    public @Nullable ThingHandler getThingHandler() {
        return handler;
    }

    @Override
    protected void startScan() {
        ControllerHandler controller = handler;
        if (controller != null) {
            controller.refresh();
        }
    }

    void publish(WorldState snapshot) {
        ControllerHandler controller = handler;
        if (controller == null) {
            return;
        }
        ThingUID bridge = controller.getThing().getUID();
        for (WorldState.Room room : snapshot.rooms().values()) {
            thingDiscovered(DiscoveryResultBuilder
                    .create(new ThingUID(DanfossLinkBindingConstants.ROOM, bridge, Integer.toString(room.id())))
                    .withBridge(bridge).withLabel(room.name())
                    .withProperty(DanfossLinkBindingConstants.ROOM_ID, room.id())
                    .withRepresentationProperty(DanfossLinkBindingConstants.ROOM_ID).build());
        }
    }

    @Override
    public void deactivate() {
        ControllerHandler controller = handler;
        handler = null;
        if (controller != null) {
            controller.discovery(null);
        }
        super.deactivate();
    }
}
