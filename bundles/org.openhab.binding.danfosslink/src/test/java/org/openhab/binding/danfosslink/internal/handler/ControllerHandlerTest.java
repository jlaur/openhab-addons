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
package org.openhab.binding.danfosslink.internal.handler;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.openhab.binding.danfosslink.internal.DanfossLinkBindingConstants.*;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentCaptor;
import org.openhab.binding.danfosslink.internal.CloudClient;
import org.openhab.binding.danfosslink.internal.Identity;
import org.openhab.binding.danfosslink.internal.Operation;
import org.openhab.binding.danfosslink.internal.PairingStore;
import org.openhab.binding.danfosslink.internal.WorldState;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.binding.ThingHandlerCallback;
import org.openhab.core.thing.binding.builder.BridgeBuilder;

import com.google.gson.JsonParser;

/**
 * Verifies that an in-flight response cannot publish after Bridge disposal.
 *
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
@Timeout(30)
class ControllerHandlerTest {
    @Test
    void disposalRejectsLateResponseAndReinitializationRecovers() throws Exception {
        var bridge = BridgeBuilder.create(THING_TYPE_CONTROLLER, "test").build();
        var identity = Identity.generate();
        var store = mock(PairingStore.class);
        when(store.load(anyString())).thenReturn(new PairingStore.Credentials(identity, identity.certificate));
        var client = mock(CloudClient.class);
        var session = mock(CloudClient.Session.class);
        when(client.open(any())).thenReturn(session);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var closed = new CountDownLatch(1);
        var online = new CountDownLatch(1);
        var state = new WorldState(Map.of(), null, "serial", "firmware");
        when(session.world()).thenAnswer(call -> {
            entered.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS));
            return state;
        });
        doAnswer(call -> {
            closed.countDown();
            return null;
        }).when(session).close();
        var callback = mock(ThingHandlerCallback.class);
        doAnswer(call -> {
            org.openhab.core.thing.ThingStatusInfo status = call.getArgument(1);
            if (status.getStatus() == ThingStatus.ONLINE) {
                online.countDown();
            }
            return null;
        }).when(callback).statusUpdated(eq(bridge), any());
        var handler = new ControllerHandler(bridge, store) {
            @Override
            CloudClient connect(Identity credentials, Operation operation) {
                return client;
            }
        };
        handler.setCallback(callback);
        try {
            handler.initialize();
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            handler.dispose();
            clearInvocations(callback);
            release.countDown();
            assertTrue(closed.await(10, TimeUnit.SECONDS));
            verify(callback, never()).statusUpdated(any(), any());
            verify(callback, never()).thingUpdated(any());
            handler.initialize();
            assertTrue(online.await(10, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            handler.dispose();
        }
    }

    @Test
    void pairingRejectsMalformedCodeBeforeConnecting() {
        var bridge = BridgeBuilder.create(THING_TYPE_CONTROLLER, "test").build();
        var handler = new ControllerHandler(bridge, mock(PairingStore.class));
        assertTrue(handler.pair("123").contains("nine digits"));
        assertTrue(handler.pair("12345678x").contains("nine digits"));
        assertTrue(handler.pair("012345678").contains("not initialized"));
    }

    @Test
    void queuedManualCommandUsesMembershipConfirmedAfterZoneChange() throws Exception {
        for (var membership : OnOffType.values()) {
            var bridge = BridgeBuilder.create(THING_TYPE_CONTROLLER, "test").build();
            var identity = Identity.generate();
            var store = mock(PairingStore.class);
            when(store.load(anyString())).thenReturn(new PairingStore.Credentials(identity, identity.certificate));
            var client = mock(CloudClient.class);
            var session = mock(CloudClient.Session.class);
            when(client.open(any())).thenReturn(session);
            String initialZone = membership == OnOffType.ON ? "None" : "Living";
            String targetZone = membership == OnOffType.ON ? "Living" : "None";
            var json = JsonParser.parseString("{\"allRooms\":[{\"id\":0,\"zone\":\"" + initialZone + "\"}]}")
                    .getAsJsonObject();
            var snapshot = new AtomicReference<>(WorldState.parse(json));
            when(session.world()).thenAnswer(call -> snapshot.get());
            doAnswer(call -> {
                WorldState.Patch patch = call.getArgument(0);
                if (patch.body().has("zone")) {
                    json.getAsJsonArray("allRooms").get(0).getAsJsonObject().addProperty("zone", targetZone);
                    snapshot.set(WorldState.parse(json));
                }
                return null;
            }).when(session).apply(any());
            var handler = new ControllerHandler(bridge, store) {
                @Override
                CloudClient connect(Identity credentials, Operation operation) {
                    return client;
                }
            };
            var callback = mock(ThingHandlerCallback.class);
            var queued = new AtomicBoolean();
            var finished = new CountDownLatch(1);
            doAnswer(call -> {
                org.openhab.core.thing.ThingStatusInfo status = call.getArgument(1);
                if (status.getStatus() == ThingStatus.ONLINE) {
                    if (queued.compareAndSet(false, true)) {
                        // Queue room and zone commands while the initial operation is still active.
                        handler.roomCommand(0, CHANNEL_LIVING_ZONE, membership);
                        handler.roomCommand(0, CHANNEL_MANUAL, OnOffType.ON);
                        handler.livingZoneCommand(CHANNEL_MANUAL, OnOffType.OFF);
                    } else {
                        finished.countDown();
                    }
                }
                return null;
            }).when(callback).statusUpdated(eq(bridge), any());
            handler.setCallback(callback);
            try {
                handler.initialize();
                assertTrue(finished.await(10, TimeUnit.SECONDS));
                var patches = ArgumentCaptor.forClass(WorldState.Patch.class);
                verify(session, times(3)).apply(patches.capture());
                var sent = patches.getAllValues();
                assertEquals(targetZone, sent.get(0).body().get("zone").getAsString());
                assertEquals("/api/zones/living", sent.get(2).uri());
                assertEquals("Schedule", sent.get(2).body().get("regulationType").getAsString());
                assertEquals(membership == OnOffType.ON ? "/api/zones/living" : "/api/rooms/0", sent.get(1).uri());
            } finally {
                handler.dispose();
            }
        }
    }

    @Test
    void rejectedMembershipReportsStatusAndFailedWriteIsNotReplayed() throws Exception {
        var bridge = BridgeBuilder.create(THING_TYPE_CONTROLLER, "test").build();
        var identity = Identity.generate();
        var store = mock(PairingStore.class);
        when(store.load(anyString())).thenReturn(new PairingStore.Credentials(identity, identity.certificate));
        var client = mock(CloudClient.class);
        var session = mock(CloudClient.Session.class);
        when(client.open(any())).thenReturn(session);
        when(session.world()).thenReturn(WorldState.parse(JsonParser.parseString("""
                {"allRooms":[{"id":0,"zone":"None"}]}
                """).getAsJsonObject()));
        doThrow(new CloudClient.ControllerResponseException(400)).when(session).apply(any());
        var handler = new ControllerHandler(bridge, store) {
            @Override
            CloudClient connect(Identity credentials, Operation operation) {
                return client;
            }
        };
        var callback = mock(ThingHandlerCallback.class);
        var queued = new AtomicBoolean();
        var failed = new CountDownLatch(1);
        var recovered = new CountDownLatch(1);
        var description = new AtomicReference<String>();
        doAnswer(call -> {
            org.openhab.core.thing.ThingStatusInfo status = call.getArgument(1);
            if (status.getStatus() == ThingStatus.ONLINE) {
                if (queued.compareAndSet(false, true)) {
                    handler.roomCommand(0, CHANNEL_LIVING_ZONE, OnOffType.ON);
                } else {
                    recovered.countDown();
                }
            } else if (status.getStatus() == ThingStatus.OFFLINE) {
                description.set(java.util.Objects.requireNonNull(status.getDescription()));
                failed.countDown();
            }
            return null;
        }).when(callback).statusUpdated(eq(bridge), any());
        handler.setCallback(callback);
        try {
            handler.initialize();
            assertTrue(failed.await(10, TimeUnit.SECONDS));
            assertEquals("Controller returned HTTP 400 while writing /api/rooms/0", description.get());
            handler.dispose();
            handler.initialize();
            assertTrue(recovered.await(10, TimeUnit.SECONDS));
            verify(session, times(1)).apply(any());
        } finally {
            handler.dispose();
        }
    }
}
