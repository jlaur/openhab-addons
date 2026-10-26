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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.binding.nest.internal.nativeapi.dto.NativeDtos.Protect;
import org.openhab.core.config.discovery.DiscoveryResult;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.ThingStatusInfo;
import org.openhab.core.thing.binding.builder.BridgeBuilder;

/**
 * Tests room labels without changing discovery identity or requiring room metadata.
 *
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
class NativeDiscoveryServiceTest {
    @Test
    void discoveryUsesRoomNamesAndKeepsIdentityAcrossRenames() {
        var bridge = BridgeBuilder.create(NativeThingHandlerFactory.ACCOUNT, "rooms").build();
        bridge.setStatusInfo(new ThingStatusInfo(ThingStatus.ONLINE, ThingStatusDetail.NONE, null));
        var account = mock(NativeAccountHandler.class);
        when(account.getThing()).thenReturn(bridge);
        var hallway = new Protect();
        hallway.whereId = "hall";
        var bedroom = new Protect();
        bedroom.whereId = "bed";
        when(account.devices()).thenReturn(Map.of("one", hallway, "two", bedroom, "three", new Protect()));
        when(account.rooms()).thenReturn(Map.of("hall", "Hallway", "bed", "Bedroom"));
        when(account.structures()).thenReturn(Map.of());
        var results = new ArrayList<DiscoveryResult>();
        var service = new NativeDiscoveryService() {
            @Override
            protected void thingDiscovered(DiscoveryResult result) {
                results.add(result);
            }
        };
        service.setThingHandler(account);
        service.startScan();
        assertEquals(3, results.size());
        var first = results.stream().filter(r -> "one".equals(r.getProperties().get("deviceId"))).findFirst()
                .orElseThrow();
        assertEquals("Nest Protect - Hallway", first.getLabel());
        assertEquals("deviceId", first.getRepresentationProperty());
        assertEquals(bridge.getUID(), first.getBridgeUID());
        assertTrue(results.stream().anyMatch(r -> "Nest Protect - Bedroom".equals(r.getLabel())));
        assertTrue(results.stream().anyMatch(r -> "Nest Protect three".equals(r.getLabel())));
        when(account.rooms()).thenReturn(Map.of("hall", "Landing"));
        results.clear();
        service.startScan();
        var renamed = results.stream().filter(r -> "one".equals(r.getProperties().get("deviceId"))).findFirst()
                .orElseThrow();
        assertEquals(first.getThingUID(), renamed.getThingUID());
        assertEquals("Nest Protect - Landing", renamed.getLabel());
        assertTrue(results.stream().anyMatch(r -> "Nest Protect two".equals(r.getLabel())));
        bridge.setStatusInfo(new ThingStatusInfo(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, null));
        results.clear();
        service.startScan();
        assertTrue(results.isEmpty());
    }
}
