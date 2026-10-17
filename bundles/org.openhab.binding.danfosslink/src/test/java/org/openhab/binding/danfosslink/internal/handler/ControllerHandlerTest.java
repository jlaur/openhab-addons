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
        var state = new WorldState(Map.of(), null, "serial", "firmware", "Danfoss",
                java.time.ZonedDateTime.parse("2026-10-16T14:30:00+02:00"), null, null, null);
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
            verify(callback, never()).stateUpdated(any(), any());
            handler.initialize();
            assertTrue(online.await(10, TimeUnit.SECONDS));
            assertEquals("2026-10-16T14:30:00+02:00", bridge.getProperties().get("systemTime"));
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
    void queuedManualCommandUsesFreshMembership() throws Exception {
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
                        json.getAsJsonArray("allRooms").get(0).getAsJsonObject().addProperty("zone", targetZone);
                        snapshot.set(WorldState.parse(json));
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
                verify(session, times(2)).apply(patches.capture());
                var sent = patches.getAllValues();
                assertEquals("/api/zones/living", sent.get(1).uri());
                assertEquals("Schedule", sent.get(1).body().get("regulationType").getAsString());
                assertEquals(membership == OnOffType.ON ? "/api/zones/living" : "/api/rooms/0", sent.get(0).uri());
            } finally {
                handler.dispose();
            }
        }
    }

    @Test
    void rejectedCommandReportsStatusAndFailedWriteIsNotReplayed() throws Exception {
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
                    handler.roomCommand(0, CHANNEL_MANUAL, OnOffType.ON);
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

    @Test
    void vacationActivationReadsBackAcceptedOrRejectedState() throws Exception {
        for (boolean reject : new boolean[] { false, true }) {
            var bridge = BridgeBuilder.create(THING_TYPE_CONTROLLER, "vacation-test").build();
            var identity = Identity.generate();
            var store = mock(PairingStore.class);
            when(store.load(anyString())).thenReturn(new PairingStore.Credentials(identity, identity.certificate));
            var client = mock(CloudClient.class);
            var session = mock(CloudClient.Session.class);
            when(client.open(any())).thenReturn(session);
            var inactive = new WorldState(Map.of(), null, null, null, null, null,
                    new WorldState.Mode("Inactive", null, null, null), null, null);
            var snapshot = new AtomicReference<>(inactive);
            when(session.world()).thenAnswer(call -> snapshot.get());
            doAnswer(call -> {
                if (reject) {
                    throw new CloudClient.ControllerResponseException(400);
                }
                snapshot.set(new WorldState(Map.of(), null, null, null, null, null,
                        new WorldState.Mode("Active", null, null, null), null, null));
                return null;
            }).when(session).vacation(any());
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
                        handler.handleCommand(new org.openhab.core.thing.ChannelUID(bridge.getUID(), CHANNEL_VACATION),
                                OnOffType.ON);
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
                var body = ArgumentCaptor.forClass(com.google.gson.JsonObject.class);
                verify(session, times(1)).vacation(body.capture());
                assertEquals(JsonParser.parseString("{\"type\":\"Vacation\",\"state\":\"Active\"}"), body.getValue());
                verify(session, times(3)).world();
                verify(callback, atLeastOnce()).stateUpdated(
                        new org.openhab.core.thing.ChannelUID(bridge.getUID(), CHANNEL_VACATION),
                        reject ? OnOffType.OFF : OnOffType.ON);
            } finally {
                handler.dispose();
            }
        }
    }

    @Test
    void queuedVacationDateEditsUseReadbackFromPreviousEdit() throws Exception {
        var bridge = BridgeBuilder.create(THING_TYPE_CONTROLLER, "vacation-edit-test").build();
        var identity = Identity.generate();
        var store = mock(PairingStore.class);
        when(store.load(anyString())).thenReturn(new PairingStore.Credentials(identity, identity.certificate));
        var client = mock(CloudClient.class);
        var session = mock(CloudClient.Session.class);
        when(client.open(any())).thenReturn(session);
        var now = java.time.Instant.parse("2026-10-17T12:00:00Z");
        var controllerNow = now.minusSeconds(1500).atZone(java.time.ZoneOffset.UTC);
        var start = now.plusSeconds(86400);
        var end = start.plusSeconds(86400);
        var laterEnd = end.plusSeconds(86400);
        var snapshot = new AtomicReference<>(new WorldState(Map.of(), null, null, null, null, controllerNow,
                new WorldState.Mode("Planned", start.minusSeconds(1500), end.minusSeconds(1500), null), null, null));
        when(session.world()).thenAnswer(call -> snapshot.get());
        doAnswer(call -> {
            com.google.gson.JsonObject body = call.getArgument(0);
            snapshot.set(new WorldState(Map.of(), null, null, null, null, controllerNow,
                    new WorldState.Mode("Planned", java.time.Instant.parse(body.get("startTime").getAsString()),
                            java.time.Instant.parse(body.get("endTime").getAsString()), null),
                    null, null));
            return null;
        }).when(session).vacation(any());
        var handler = new ControllerHandler(bridge, store, java.time.Clock.fixed(now, java.time.ZoneOffset.UTC)) {
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
                    handler.handleCommand(new org.openhab.core.thing.ChannelUID(bridge.getUID(), CHANNEL_VACATION_END),
                            new org.openhab.core.library.types.DateTimeType(laterEnd));
                    handler.handleCommand(
                            new org.openhab.core.thing.ChannelUID(bridge.getUID(), CHANNEL_VACATION_START),
                            new org.openhab.core.library.types.DateTimeType(end));
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
            var bodies = ArgumentCaptor.forClass(com.google.gson.JsonObject.class);
            verify(session, times(2)).vacation(bodies.capture());
            assertEquals(start.minusSeconds(1500).toString(),
                    bodies.getAllValues().get(0).get("startTime").getAsString());
            assertEquals(end.minusSeconds(1500).toString(),
                    bodies.getAllValues().get(1).get("startTime").getAsString());
            assertEquals(laterEnd.minusSeconds(1500).toString(),
                    bodies.getAllValues().get(1).get("endTime").getAsString());
            verify(session, times(4)).world();
            verify(callback, atLeastOnce()).stateUpdated(
                    new org.openhab.core.thing.ChannelUID(bridge.getUID(), CHANNEL_VACATION_END),
                    new org.openhab.core.library.types.DateTimeType(laterEnd));
        } finally {
            handler.dispose();
        }
    }

    @Test
    void clockWarningUsesInstantThresholdAndResetsOnInitialize() throws Exception {
        var now = java.time.Instant.parse("2026-10-16T12:00:00Z");
        for (long seconds : new long[] { -301, -300, 0, 300, 301 }) {
            var bridge = BridgeBuilder.create(THING_TYPE_CONTROLLER, "clock").build();
            var identity = Identity.generate();
            var store = mock(PairingStore.class);
            when(store.load(anyString())).thenReturn(new PairingStore.Credentials(identity, identity.certificate));
            var client = mock(CloudClient.class);
            var session = mock(CloudClient.Session.class);
            when(client.open(any())).thenReturn(session);
            var time = now.plusSeconds(seconds).atZone(java.time.ZoneOffset.ofHours(2));
            when(session.world()).thenReturn(new WorldState(Map.of(), null, null, null, null, time, null, null, null));
            var logger = mock(org.slf4j.Logger.class);
            try (var factory = mockStatic(org.slf4j.LoggerFactory.class, CALLS_REAL_METHODS)) {
                factory.when(() -> org.slf4j.LoggerFactory.getLogger(ControllerHandler.class)).thenReturn(logger);
                var handler = new ControllerHandler(bridge, store,
                        java.time.Clock.fixed(now, java.time.ZoneOffset.UTC)) {
                    @Override
                    CloudClient connect(Identity credentials, Operation operation) {
                        return client;
                    }
                };
                var callback = mock(ThingHandlerCallback.class);
                handler.setCallback(callback);
                try {
                    for (int lifecycle = 1; lifecycle <= 2; lifecycle++) {
                        var finished = new CountDownLatch(1);
                        var reports = new java.util.concurrent.atomic.AtomicInteger();
                        doAnswer(call -> {
                            org.openhab.core.thing.ThingStatusInfo status = call.getArgument(1);
                            if (status.getStatus() == ThingStatus.ONLINE) {
                                if (reports.incrementAndGet() == 1) {
                                    // Queue another snapshot in the same lifecycle to verify warning suppression.
                                    handler.livingZoneCommand(CHANNEL_MANUAL, OnOffType.ON);
                                } else {
                                    finished.countDown();
                                }
                            }
                            return null;
                        }).when(callback).statusUpdated(eq(bridge), any());
                        handler.initialize();
                        assertTrue(finished.await(10, TimeUnit.SECONDS));
                        handler.dispose();
                        verify(logger, times(Math.abs(seconds) > 300 ? lifecycle : 0)).warn(anyString(),
                                eq(bridge.getUID()), eq(time));
                    }
                } finally {
                    handler.dispose();
                }
            }
        }
    }

    @Test
    void pauseCommandsUseConfirmedReadbackIncludingRejectionAndCancellation() throws Exception {
        for (int failure : new int[] { 0, 400, -1 }) {
            var now = java.time.Instant.parse("2026-10-17T12:00:00Z");
            var bridge = BridgeBuilder.create(THING_TYPE_CONTROLLER, "pause-test").build();
            var identity = Identity.generate();
            var store = mock(PairingStore.class);
            when(store.load(anyString())).thenReturn(new PairingStore.Credentials(identity, identity.certificate));
            var client = mock(CloudClient.class);
            var session = mock(CloudClient.Session.class);
            when(client.open(any())).thenReturn(session);
            var initialClock = now.plusSeconds(1500).atZone(java.time.ZoneOffset.UTC);
            var correctedClock = now.minusSeconds(600).atZone(java.time.ZoneOffset.UTC);
            var inactive = new WorldState(Map.of(), null, null, null, null, correctedClock, null,
                    new WorldState.Mode("Inactive", null, null, null), null);
            var snapshot = new AtomicReference<>(
                    new WorldState(Map.of(), null, null, null, null, initialClock, null, inactive.pause(), null));
            when(session.world()).thenAnswer(call -> snapshot.get());
            doAnswer(call -> {
                com.google.gson.JsonObject body = call.getArgument(0);
                if ("Inactive".equals(body.get("state").getAsString())) {
                    snapshot.set(inactive);
                } else if (failure == -1) {
                    throw new java.io.IOException("Connection closed");
                } else if (failure == 400) {
                    throw new CloudClient.ControllerResponseException(400);
                } else {
                    // Simulate a controller adjusting the requested five minutes to one hour.
                    snapshot.set(new WorldState(Map.of(), null, null, null, null, correctedClock, null,
                            new WorldState.Mode("Active", null, now.plusSeconds(3000), null), null));
                }
                return null;
            }).when(session).pause(any());
            var handler = new ControllerHandler(bridge, store, java.time.Clock.fixed(now, java.time.ZoneOffset.UTC)) {
                @Override
                CloudClient connect(Identity credentials, Operation operation) {
                    return client;
                }
            };
            var callback = mock(ThingHandlerCallback.class);
            var reports = new java.util.concurrent.atomic.AtomicInteger();
            var finished = new CountDownLatch(1);
            var pause = new org.openhab.core.thing.ChannelUID(bridge.getUID(), CHANNEL_PAUSE);
            var until = new org.openhab.core.thing.ChannelUID(bridge.getUID(), CHANNEL_PAUSE_UNTIL);
            doAnswer(call -> {
                org.openhab.core.thing.ThingStatusInfo status = call.getArgument(1);
                if (status.getStatus() == ThingStatus.OFFLINE && failure == -1) {
                    // Queue independent work to recover without replaying the failed Pause request.
                    handler.livingZoneCommand(CHANNEL_MANUAL, OnOffType.OFF);
                }
                if (status.getStatus() == ThingStatus.ONLINE) {
                    switch (reports.incrementAndGet()) {
                        case 1 -> {
                            // Simulate the controller clock being corrected between polls.
                            snapshot.set(inactive);
                            handler.handleCommand(pause, OnOffType.ON);
                            handler.handleCommand(until,
                                    new org.openhab.core.library.types.DateTimeType(now.plusSeconds(300)));
                        }
                        case 2 -> handler.handleCommand(pause, OnOffType.OFF);
                        default -> finished.countDown();
                    }
                }
                return null;
            }).when(callback).statusUpdated(eq(bridge), any());
            handler.setCallback(callback);
            try {
                handler.initialize();
                assertTrue(finished.await(10, TimeUnit.SECONDS));
                var bodies = ArgumentCaptor.forClass(com.google.gson.JsonObject.class);
                verify(session, times(2)).pause(bodies.capture());
                assertEquals(now.minusSeconds(300).toString(),
                        bodies.getAllValues().get(0).get("endTime").getAsString());
                assertEquals("Inactive", bodies.getAllValues().get(1).get("state").getAsString());
                verify(callback, never()).stateUpdated(until,
                        new org.openhab.core.library.types.DateTimeType(now.plusSeconds(300)));
                if (failure == 0) {
                    verify(callback, atLeastOnce()).stateUpdated(until,
                            new org.openhab.core.library.types.DateTimeType(now.plusSeconds(3600)));
                }
                verify(callback, atLeastOnce()).stateUpdated(pause, OnOffType.OFF);
                verify(callback, atLeastOnce()).stateUpdated(until, org.openhab.core.types.UnDefType.UNDEF);
                if (failure == -1) {
                    verify(callback, atLeastOnce()).stateUpdated(pause, org.openhab.core.types.UnDefType.UNDEF);
                }
                verify(session, never()).vacation(any());
            } finally {
                handler.dispose();
            }
        }
    }

    @Test
    void homeCapturesSelectionAndSupportsReapplyCancelAndRecoveryWithoutReplay() throws Exception {
        for (int failure : new int[] { 0, 400, -1 }) {
            var bridge = BridgeBuilder.create(THING_TYPE_CONTROLLER, "home-test").build();
            var identity = Identity.generate();
            var store = mock(PairingStore.class);
            when(store.load(anyString())).thenReturn(new PairingStore.Credentials(identity, identity.certificate));
            var client = mock(CloudClient.class);
            var session = mock(CloudClient.Session.class);
            when(client.open(any())).thenReturn(session);
            var json = JsonParser.parseString("""
                    {"allRooms":[
                    {"id":0,"zone":"None","regulation":{"type":"Schedule"}},
                    {"id":1,"zone":"Living","regulation":{"type":"Schedule"}},
                    {"id":2,"zone":"Living","regulation":{"type":"Schedule"}}],
                    "modes":[{"type":"AtHome","state":"Inactive"}]}
                    """).getAsJsonObject();
            when(session.world()).thenAnswer(call -> WorldState.parse(json));
            var writes = new java.util.concurrent.atomic.AtomicInteger();
            doAnswer(call -> {
                if (writes.getAndIncrement() == 0) {
                    if (failure == 400) {
                        throw new CloudClient.ControllerResponseException(400);
                    } else if (failure == -1) {
                        throw new java.io.IOException("Connection closed");
                    }
                }
                com.google.gson.JsonObject body = call.getArgument(0);
                json.getAsJsonArray("modes").get(0).getAsJsonObject().add("state", body.get("state"));
                return null;
            }).when(session).home(any());
            var handler = new ControllerHandler(bridge, store) {
                @Override
                CloudClient connect(Identity credentials, Operation operation) {
                    return client;
                }
            };
            var callback = mock(ThingHandlerCallback.class);
            var reports = new java.util.concurrent.atomic.AtomicInteger();
            var finished = new CountDownLatch(1);
            var channel = new org.openhab.core.thing.ChannelUID(bridge.getUID(), CHANNEL_HOME);
            doAnswer(call -> {
                org.openhab.core.thing.ThingStatusInfo status = call.getArgument(1);
                if (status.getStatus() == ThingStatus.OFFLINE && failure == -1) {
                    handler.livingZoneCommand(CHANNEL_MANUAL, OnOffType.OFF);
                }
                if (status.getStatus() == ThingStatus.ONLINE) {
                    switch (reports.incrementAndGet()) {
                        case 1 -> {
                            handler.selectHome(0, OnOffType.ON);
                            verify(session, never()).home(any());
                            handler.handleCommand(channel, OnOffType.ON);
                            handler.selectHome(0, OnOffType.OFF);
                            handler.selectHome(null, OnOffType.ON);
                        }
                        case 2 -> handler.handleCommand(channel, OnOffType.ON);
                        case 3 -> handler.handleCommand(channel, OnOffType.OFF);
                        case 4 -> handler.handleCommand(channel, OnOffType.ON);
                        default -> finished.countDown();
                    }
                }
                return null;
            }).when(callback).statusUpdated(eq(bridge), any());
            handler.setCallback(callback);
            try {
                handler.initialize();
                assertTrue(finished.await(10, TimeUnit.SECONDS));
                var bodies = ArgumentCaptor.forClass(com.google.gson.JsonObject.class);
                verify(session, times(4)).home(bodies.capture());
                assertEquals(JsonParser.parseString("[0]"), bodies.getAllValues().get(0).get("rooms"));
                assertEquals(JsonParser.parseString("[1,2]"), bodies.getAllValues().get(1).get("rooms"));
                assertEquals(JsonParser.parseString("{\"type\":\"AtHome\",\"state\":\"Inactive\"}"),
                        bodies.getAllValues().get(2));
                assertEquals(JsonParser.parseString("[1,2]"), bodies.getAllValues().get(3).get("rooms"));
                verify(callback, atLeastOnce()).stateUpdated(channel, OnOffType.ON);
                verify(callback, atLeastOnce()).stateUpdated(channel, OnOffType.OFF);
                if (failure == -1) {
                    verify(callback, atLeastOnce()).stateUpdated(channel, org.openhab.core.types.UnDefType.UNDEF);
                }
                verify(session, never()).pause(any());
                verify(session, never()).vacation(any());
            } finally {
                handler.dispose();
            }
        }
    }
}
