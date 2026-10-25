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
 * Discovers Protects from the authenticated account's current snapshot.
 *
 * @author Jacob Laursen - Initial contribution
 */
@Component(scope = ServiceScope.PROTOTYPE, service = NativeDiscoveryService.class)
@NonNullByDefault
public class NativeDiscoveryService extends AbstractThingHandlerDiscoveryService<NativeAccountHandler> {
    public NativeDiscoveryService() {
        super(NativeAccountHandler.class, Set.of(NativeThingHandlerFactory.PROTECT), 10, false);
    }

    @Override
    protected void startScan() {
        if (thingHandler.getThing().getStatus() != ThingStatus.ONLINE) {
            return;
        }
        ThingUID bridge = thingHandler.getThing().getUID();
        for (String id : thingHandler.devices().keySet()) {
            String uid = UUID.nameUUIDFromBytes(id.getBytes(StandardCharsets.UTF_8)).toString();
            thingDiscovered(DiscoveryResultBuilder.create(new ThingUID(NativeThingHandlerFactory.PROTECT, bridge, uid))
                    .withBridge(bridge).withLabel("Nest Protect " + id).withProperty("deviceId", id)
                    .withRepresentationProperty("deviceId").build());
        }
    }
}
