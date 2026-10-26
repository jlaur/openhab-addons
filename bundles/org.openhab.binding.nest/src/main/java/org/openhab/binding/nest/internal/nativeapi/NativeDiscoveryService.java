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
package org.openhab.binding.nest.internal.nativeapi;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.config.discovery.AbstractThingHandlerDiscoveryService;
import org.openhab.core.config.discovery.DiscoveryResultBuilder;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingUID;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ServiceScope;

/**
 * Discovers Protects and structures from the authenticated account's current snapshot.
 *
 * @author Jacob Laursen - Initial contribution
 */
@Component(scope = ServiceScope.PROTOTYPE, service = NativeDiscoveryService.class)
@NonNullByDefault
public class NativeDiscoveryService extends AbstractThingHandlerDiscoveryService<NativeAccountHandler> {
    public NativeDiscoveryService() {
        super(NativeAccountHandler.class,
                Set.of(NativeThingHandlerFactory.PROTECT, NativeThingHandlerFactory.STRUCTURE), 10, false);
    }

    @Override
    protected void startScan() {
        if (thingHandler.getThing().getStatus() != ThingStatus.ONLINE) {
            return;
        }
        ThingUID bridge = thingHandler.getThing().getUID();
        var rooms = thingHandler.rooms();
        thingHandler.devices().forEach((id, protect) -> {
            String uid = UUID.nameUUIDFromBytes(id.getBytes(StandardCharsets.UTF_8)).toString();
            String roomId = protect.whereId;
            String room = roomId == null ? null : rooms.get(roomId);
            thingDiscovered(DiscoveryResultBuilder.create(new ThingUID(NativeThingHandlerFactory.PROTECT, bridge, uid))
                    .withBridge(bridge).withLabel(room == null ? "Nest Protect " + id : "Nest Protect - " + room)
                    .withProperty("deviceId", id).withRepresentationProperty("deviceId").build());
        });
        thingHandler.structures().forEach((id, structure) -> {
            String uid = UUID.nameUUIDFromBytes(id.getBytes(StandardCharsets.UTF_8)).toString();
            String name = structure.name;
            thingDiscovered(
                    DiscoveryResultBuilder.create(new ThingUID(NativeThingHandlerFactory.STRUCTURE, bridge, uid))
                            .withBridge(bridge).withLabel(name == null || name.isBlank() ? "Nest Home " + id : name)
                            .withProperty("structureId", id).withRepresentationProperty("structureId").build());
        });
    }
}
