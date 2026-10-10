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
package org.openhab.binding.veluxactive.internal.discovery;

import static org.openhab.binding.veluxactive.internal.VeluxActiveBindingConstants.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.veluxactive.internal.VeluxActiveApi;
import org.openhab.binding.veluxactive.internal.handler.VeluxActiveAccountHandler;
import org.openhab.core.config.discovery.AbstractThingHandlerDiscoveryService;
import org.openhab.core.config.discovery.DiscoveryResultBuilder;
import org.openhab.core.thing.ThingUID;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ServiceScope;

/**
 * Discovers gateways, battery modules and NXO openings from account topology.
 *
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
@Component(scope = ServiceScope.PROTOTYPE, service = VeluxActiveDiscoveryService.class)
public class VeluxActiveDiscoveryService extends AbstractThingHandlerDiscoveryService<VeluxActiveAccountHandler> {
    private long generation;

    public VeluxActiveDiscoveryService() {
        super(VeluxActiveAccountHandler.class,
                Set.of(THING_TYPE_GATEWAY, THING_TYPE_INDOOR_SENSOR, THING_TYPE_DEPARTURE_SWITCH, THING_TYPE_OPENING),
                60, false);
    }

    @Override
    public void startScan() {
        long scan;
        synchronized (this) {
            scan = ++generation;
        }
        thingHandler.discover(modules -> publish(scan, modules));
    }

    private synchronized void publish(long scan, List<VeluxActiveApi.ModuleInfo> modules) {
        if (scan != generation) {
            return;
        }
        ThingUID bridgeUID = thingHandler.getThing().getUID();
        for (VeluxActiveApi.ModuleInfo module : modules) {
            String identity = module.homeId() + "/" + module.moduleId();
            String id = UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString();
            String roomName = module.roomName();
            String label = "NXO".equals(module.type()) && roomName != null && !roomName.isBlank()
                    ? module.name() + " (" + roomName + ")"
                    : module.name();
            thingDiscovered(DiscoveryResultBuilder.create(new ThingUID(switch (module.type()) {
                case "NXS" -> THING_TYPE_INDOOR_SENSOR;
                case "NXD" -> THING_TYPE_DEPARTURE_SWITCH;
                case "NXO" -> THING_TYPE_OPENING;
                default -> THING_TYPE_GATEWAY;
            }, bridgeUID, id)).withBridge(bridgeUID).withLabel(label).withProperty("homeId", module.homeId())
                    .withProperty("moduleId", module.moduleId()).withRepresentationProperty("moduleId").build());
        }
    }

    @Override
    protected synchronized void stopScan() {
        generation++;
        super.stopScan();
    }

    @Override
    public synchronized void dispose() {
        generation++;
        super.dispose();
    }
}
