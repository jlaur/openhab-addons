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

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentCaptor;
import org.openhab.binding.danfosslink.internal.CloudClient;
import org.openhab.binding.danfosslink.internal.DanfossLinkStateDescriptionProvider;
import org.openhab.binding.danfosslink.internal.PairingStore;
import org.openhab.binding.danfosslink.internal.WorldState;
import org.openhab.binding.danfosslink.internal.exception.CloudServerException;
import org.openhab.binding.danfosslink.internal.exception.ControllerConnectionTimeoutException;
import org.openhab.binding.danfosslink.internal.exception.ControllerResponseException;
import org.openhab.binding.danfosslink.internal.exception.ControllerUnavailableException;
import org.openhab.binding.danfosslink.internal.exception.RelayConnectionClosedException;
import org.openhab.binding.danfosslink.internal.protocol.Identity;
import org.openhab.binding.danfosslink.internal.protocol.Operation;
import org.openhab.binding.danfosslink.internal.protocol.dto.ModeRequestDTO;
import org.openhab.binding.danfosslink.internal.protocol.dto.ModeState;
import org.openhab.binding.danfosslink.internal.protocol.dto.ModeType;
import org.openhab.core.library.types.DateTimeType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.ThingStatusInfo;
import org.openhab.core.thing.binding.ThingHandlerCallback;
import org.openhab.core.thing.binding.builder.BridgeBuilder;
import org.openhab.core.types.UnDefType;

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
                java.time.ZonedDateTime.parse("2026-10-16T14:30:00+02:00"), null, null, null, null, null, null);
        when(session.world(any())).thenAnswer(call -> {
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
            ThingStatusInfo status = call.getArgument(1);
            if (status.getStatus() == ThingStatus.ONLINE) {
                online.countDown();
            }
            return null;
        }).when(callback).statusUpdated(eq(bridge), any());
        var handler = new ControllerHandler(bridge, store, mock(DanfossLinkStateDescriptionProvider.class)) {
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
    void connectionFailuresPublishSpecificStatusAndRecover() throws Exception {
        for (IOException failure : new IOException[] { new ControllerUnavailableException(), new CloudServerException(),
                new ControllerConnectionTimeoutException(),
                new RelayConnectionClosedException(new java.io.EOFException()) }) {
            var bridge = BridgeBuilder.create(THING_TYPE_CONTROLLER, "failure").build();
            var identity = Identity.generate();
            var store = mock(PairingStore.class);
            when(store.load(anyString())).thenReturn(new PairingStore.Credentials(identity, identity.certificate));
            var client = mock(CloudClient.class);
            var session = mock(CloudClient.Session.class);
            when(client.open(any())).thenThrow(failure).thenReturn(session);
            when(session.world(any())).thenReturn(
                    new WorldState(Map.of(), null, null, null, null, null, null, null, null, null, null, null));
            var handler = new ControllerHandler(bridge, store, mock(DanfossLinkStateDescriptionProvider.class)) {
                @Override
                CloudClient connect(Identity credentials, Operation operation) {
                    return client;
                }
            };
            var callback = mock(ThingHandlerCallback.class);
            var offline = new CountDownLatch(1);
            var online = new CountDownLatch(1);
            var status = new AtomicReference<ThingStatusInfo>();
            doAnswer(call -> {
                ThingStatusInfo info = call.getArgument(1);
                if (info.getStatus() == ThingStatus.OFFLINE) {
                    status.set(info);
                    offline.countDown();
                } else if (info.getStatus() == ThingStatus.ONLINE) {
                    online.countDown();
                }
                return null;
            }).when(callback).statusUpdated(eq(bridge), any());
            handler.setCallback(callback);
            try {
                handler.initialize();
                assertTrue(offline.await(10, TimeUnit.SECONDS));
                assertEquals(ThingStatusDetail.COMMUNICATION_ERROR,
                        Objects.requireNonNull(status.get()).getStatusDetail());
                assertEquals(failure.getMessage() + "; will retry",
                        Objects.requireNonNull(status.get()).getDescription());
                handler.dispose();
                handler.initialize();
                assertTrue(online.await(10, TimeUnit.SECONDS));
            } finally {
                handler.dispose();
            }
        }
    }

    @Test
    void publishesAlarmsAndClearsUnavailableStatesBeforeRecovery() throws Exception {
        var bridge = BridgeBuilder.create(THING_TYPE_CONTROLLER, "alarms").build();
        var identity = Identity.generate();
        var store = mock(PairingStore.class);
        when(store.load(anyString())).thenReturn(new PairingStore.Credentials(identity, identity.certificate));
        var client = mock(CloudClient.class);
        var session = mock(CloudClient.Session.class);
        when(client.open(any())).thenReturn(session);
        var handler = new ControllerHandler(bridge, store, mock(DanfossLinkStateDescriptionProvider.class)) {
            @Override
            CloudClient connect(Identity credentials, Operation operation) {
                return client;
            }
        };
        var callback = mock(ThingHandlerCallback.class);
        handler.setCallback(callback);
        try {
            for (String alarms : new String[] { "[{\"id\":42,\"isRead\":true}]", "null", "failure", "[]" }) {
                var finished = new CountDownLatch(1);
                doAnswer(call -> {
                    ThingStatusInfo info = call.getArgument(1);
                    if (info.getStatus() == ThingStatus.ONLINE || info.getStatus() == ThingStatus.OFFLINE) {
                        finished.countDown();
                    }
                    return null;
                }).when(callback).statusUpdated(eq(bridge), any());
                // Reset a previous throwing stub without invoking it during stubbing.
                if ("failure".equals(alarms)) {
                    doThrow(new IOException("Connection failed")).when(session).world(any());
                } else {
                    doReturn(WorldState.parse(JsonParser
                            .parseString("{\"allRooms\":[],\"allUnresolvedAlarms\":" + alarms + "}").getAsJsonObject()))
                            .when(session).world(any());
                }
                clearInvocations(callback);
                handler.initialize();
                assertTrue(finished.await(10, TimeUnit.SECONDS));
                handler.dispose();
                boolean unknown = "null".equals(alarms) || "failure".equals(alarms);
                verify(callback).stateUpdated(new ChannelUID(bridge.getUID(), CHANNEL_ALARM),
                        unknown ? UnDefType.UNDEF : "[]".equals(alarms) ? OnOffType.OFF : OnOffType.ON);
                verify(callback).stateUpdated(new ChannelUID(bridge.getUID(), CHANNEL_ALARM_COUNT),
                        unknown ? UnDefType.UNDEF
                                : new org.openhab.core.library.types.DecimalType("[]".equals(alarms) ? 0 : 1));
                verify(callback).stateUpdated(new ChannelUID(bridge.getUID(), CHANNEL_ALARMS),
                        unknown ? UnDefType.UNDEF : new StringType(alarms));
            }
        } finally {
            handler.dispose();
        }
    }

    @Test
    void sharedScheduleReadIsDeduplicatedAndOptionalFailuresDoNotTakeBridgeOffline() throws Exception {
        for (boolean failure : new boolean[] { false, true }) {
            var bridge = spy(BridgeBuilder.create(THING_TYPE_CONTROLLER, "schedules").build());
            var roomThing = mock(org.openhab.core.thing.Thing.class);
            var zoneThing = mock(org.openhab.core.thing.Thing.class);
            var room = mock(RoomHandler.class);
            var zone = mock(LivingZoneHandler.class);
            when(roomThing.getHandler()).thenReturn(room);
            when(zoneThing.getHandler()).thenReturn(zone);
            doReturn(List.of(roomThing, zoneThing)).when(bridge).getThings();
            var roomState = new AtomicReference<org.openhab.core.types.State>();
            var zoneState = new AtomicReference<org.openhab.core.types.State>();
            when(room.scheduleRequest(any(), any())).thenReturn(new ScheduleDisplay.Request(2, roomState::set));
            when(zone.scheduleRequest(any(), any())).thenReturn(new ScheduleDisplay.Request(2, zoneState::set));
            var identity = Identity.generate();
            var store = mock(PairingStore.class);
            when(store.load(anyString())).thenReturn(new PairingStore.Credentials(identity, identity.certificate));
            var client = mock(CloudClient.class);
            var session = mock(CloudClient.Session.class);
            when(client.open(any())).thenReturn(session);
            when(session.world(any())).thenReturn(WorldState.parse(
                    JsonParser.parseString("{\"allRooms\":[{\"id\":2,\"zone\":\"Living\"}]}").getAsJsonObject()));
            String schedule = "{\"monday\":[]}";
            if (failure) {
                when(session.room(2)).thenThrow(new IOException("Schedule read failed"));
            } else {
                var details = new org.openhab.binding.danfosslink.internal.protocol.dto.RoomDetailsDTO();
                details.schedule = org.openhab.binding.danfosslink.internal.ProtocolJson.GSON.fromJson(schedule,
                        org.openhab.binding.danfosslink.internal.protocol.dto.WeeklyScheduleDTO.class);
                when(session.room(2)).thenReturn(details);
            }
            var closed = new CountDownLatch(1);
            doAnswer(call -> {
                closed.countDown();
                return null;
            }).when(session).close();
            var handler = new ControllerHandler(bridge, store, mock(DanfossLinkStateDescriptionProvider.class)) {
                @Override
                CloudClient connect(Identity credentials, Operation operation) {
                    return client;
                }
            };
            var callback = mock(ThingHandlerCallback.class);
            handler.setCallback(callback);
            try {
                handler.initialize();
                assertTrue(closed.await(10, TimeUnit.SECONDS));
                verify(session, times(1)).room(2);
                assertEquals(failure ? UnDefType.UNDEF : new StringType(schedule), roomState.get());
                assertEquals(roomState.get(), zoneState.get());
                verify(callback).statusUpdated(eq(bridge), argThat(info -> info.getStatus() == ThingStatus.ONLINE));
                verify(callback, never()).statusUpdated(eq(bridge),
                        argThat(info -> info.getStatus() == ThingStatus.OFFLINE));
                verify(room).publish(any());
                verify(zone).publish(any());
            } finally {
                handler.dispose();
            }
        }
    }

    @Test
    void pairingRejectsMalformedCodeBeforeConnecting() {
        var bridge = BridgeBuilder.create(THING_TYPE_CONTROLLER, "test").build();
        var handler = new ControllerHandler(bridge, mock(PairingStore.class),
                mock(DanfossLinkStateDescriptionProvider.class));
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
            when(session.world(any())).thenAnswer(call -> snapshot.get());
            var handler = new ControllerHandler(bridge, store, mock(DanfossLinkStateDescriptionProvider.class)) {
                @Override
                CloudClient connect(Identity credentials, Operation operation) {
                    return client;
                }
            };
            var callback = mock(ThingHandlerCallback.class);
            var queued = new AtomicBoolean();
            var finished = new CountDownLatch(1);
            doAnswer(call -> {
                ThingStatusInfo status = call.getArgument(1);
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
        when(session.world(any())).thenReturn(WorldState.parse(JsonParser.parseString("""
                {"allRooms":[{"id":0,"zone":"None"}]}
                """).getAsJsonObject()));
        doThrow(new ControllerResponseException(400)).when(session).apply(any());
        var handler = new ControllerHandler(bridge, store, mock(DanfossLinkStateDescriptionProvider.class)) {
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
            ThingStatusInfo status = call.getArgument(1);
            if (status.getStatus() == ThingStatus.ONLINE) {
                if (queued.compareAndSet(false, true)) {
                    handler.roomCommand(0, CHANNEL_MANUAL, OnOffType.ON);
                } else {
                    recovered.countDown();
                }
            } else if (status.getStatus() == ThingStatus.OFFLINE) {
                description.set(Objects.requireNonNull(status.getDescription()));
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
                    new WorldState.Mode(ModeState.INACTIVE, null, null, null), null, null, null, null, null);
            var snapshot = new AtomicReference<>(inactive);
            when(session.world(any())).thenAnswer(call -> snapshot.get());
            doAnswer(call -> {
                if (reject) {
                    throw new ControllerResponseException(400);
                }
                snapshot.set(new WorldState(Map.of(), null, null, null, null, null,
                        new WorldState.Mode(ModeState.ACTIVE, null, null, null), null, null, null, null, null));
                return null;
            }).when(session).vacation(any());
            var handler = new ControllerHandler(bridge, store, mock(DanfossLinkStateDescriptionProvider.class)) {
                @Override
                CloudClient connect(Identity credentials, Operation operation) {
                    return client;
                }
            };
            var callback = mock(ThingHandlerCallback.class);
            var queued = new AtomicBoolean();
            var finished = new CountDownLatch(1);
            doAnswer(call -> {
                ThingStatusInfo status = call.getArgument(1);
                if (status.getStatus() == ThingStatus.ONLINE) {
                    if (queued.compareAndSet(false, true)) {
                        handler.handleCommand(new ChannelUID(bridge.getUID(), CHANNEL_VACATION), OnOffType.ON);
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
                var body = ArgumentCaptor.forClass(ModeRequestDTO.class);
                verify(session, times(1)).vacation(body.capture());
                assertEquals(ModeType.VACATION, body.getValue().type);
                assertEquals(ModeState.ACTIVE, body.getValue().state);
                assertNull(body.getValue().startTime);
                assertNull(body.getValue().endTime);
                verify(session, times(3)).world(any());
                verify(callback, atLeastOnce()).stateUpdated(new ChannelUID(bridge.getUID(), CHANNEL_VACATION),
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
        var now = Instant.parse("2026-10-17T12:00:00Z");
        var offset = java.time.ZoneOffset.ofHours(2);
        var controllerNow = now.minusSeconds(1500).atZone(offset);
        var start = now.plusSeconds(86400);
        var end = start.plusSeconds(86400);
        var laterEnd = end.plusSeconds(86400);
        var snapshot = new AtomicReference<>(new WorldState(Map.of(), null, null, null, null, controllerNow,
                new WorldState.Mode(ModeState.PLANNED, start.minusSeconds(1500), end.minusSeconds(1500), offset), null,
                null, null, null, null));
        when(session.world(any())).thenAnswer(call -> snapshot.get());
        doAnswer(call -> {
            ModeRequestDTO body = call.getArgument(0);
            snapshot.set(new WorldState(Map.of(), null, null, null, null, controllerNow,
                    new WorldState.Mode(ModeState.PLANNED, Objects.requireNonNull(body.startTime).toInstant(offset),
                            Objects.requireNonNull(body.endTime).toInstant(offset), offset),
                    null, null, null, null, null));
            return null;
        }).when(session).vacation(any());
        var handler = new ControllerHandler(bridge, store, mock(DanfossLinkStateDescriptionProvider.class),
                Clock.fixed(now, ZoneOffset.UTC), () -> java.time.ZoneId.of("Europe/Copenhagen")) {
            @Override
            CloudClient connect(Identity credentials, Operation operation) {
                return client;
            }
        };
        var callback = mock(ThingHandlerCallback.class);
        var queued = new AtomicBoolean();
        var finished = new CountDownLatch(1);
        doAnswer(call -> {
            ThingStatusInfo status = call.getArgument(1);
            if (status.getStatus() == ThingStatus.ONLINE) {
                if (queued.compareAndSet(false, true)) {
                    handler.handleCommand(new ChannelUID(bridge.getUID(), CHANNEL_VACATION_END),
                            new DateTimeType(laterEnd));
                    handler.handleCommand(new ChannelUID(bridge.getUID(), CHANNEL_VACATION_START),
                            new DateTimeType(end));
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
            var bodies = ArgumentCaptor.forClass(ModeRequestDTO.class);
            verify(session, times(2)).vacation(bodies.capture());
            assertEquals(start.minusSeconds(1500),
                    Objects.requireNonNull(bodies.getAllValues().get(0).startTime).toInstant(offset));
            assertEquals(end.minusSeconds(1500),
                    Objects.requireNonNull(bodies.getAllValues().get(1).startTime).toInstant(offset));
            assertEquals(laterEnd.minusSeconds(1500),
                    Objects.requireNonNull(bodies.getAllValues().get(1).endTime).toInstant(offset));
            verify(session, times(4)).world(eq(java.time.ZoneId.of("Europe/Copenhagen")));
            verify(callback, atLeastOnce()).stateUpdated(new ChannelUID(bridge.getUID(), CHANNEL_VACATION_END),
                    new DateTimeType(laterEnd));
        } finally {
            handler.dispose();
        }
    }

    @Test
    void clockWarningUsesInstantThresholdAndResetsOnInitialize() throws Exception {
        var now = Instant.parse("2026-10-16T12:00:00Z");
        for (long seconds : new long[] { -301, -300, 0, 300, 301 }) {
            var bridge = BridgeBuilder.create(THING_TYPE_CONTROLLER, "clock").build();
            var identity = Identity.generate();
            var store = mock(PairingStore.class);
            when(store.load(anyString())).thenReturn(new PairingStore.Credentials(identity, identity.certificate));
            var client = mock(CloudClient.class);
            var session = mock(CloudClient.Session.class);
            when(client.open(any())).thenReturn(session);
            var time = now.plusSeconds(seconds).atZone(ZoneOffset.ofHours(2));
            when(session.world(any())).thenReturn(
                    new WorldState(Map.of(), null, null, null, null, time, null, null, null, null, null, null));
            var logger = mock(org.slf4j.Logger.class);
            try (var factory = mockStatic(org.slf4j.LoggerFactory.class, CALLS_REAL_METHODS)) {
                factory.when(() -> org.slf4j.LoggerFactory.getLogger(ControllerHandler.class)).thenReturn(logger);
                var handler = new ControllerHandler(bridge, store, mock(DanfossLinkStateDescriptionProvider.class),
                        Clock.fixed(now, ZoneOffset.UTC)) {
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
                        var reports = new AtomicInteger();
                        doAnswer(call -> {
                            ThingStatusInfo status = call.getArgument(1);
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
            var now = Instant.parse("2026-10-17T12:00:00Z");
            var bridge = BridgeBuilder.create(THING_TYPE_CONTROLLER, "pause-test").build();
            var identity = Identity.generate();
            var store = mock(PairingStore.class);
            when(store.load(anyString())).thenReturn(new PairingStore.Credentials(identity, identity.certificate));
            var client = mock(CloudClient.class);
            var session = mock(CloudClient.Session.class);
            when(client.open(any())).thenReturn(session);
            var initialClock = now.plusSeconds(1500).atZone(ZoneOffset.UTC);
            var correctedClock = now.minusSeconds(600).atZone(ZoneOffset.UTC);
            var inactive = new WorldState(Map.of(), null, null, null, null, correctedClock, null,
                    new WorldState.Mode(ModeState.INACTIVE, null, null, null), null, null, null, null);
            var snapshot = new AtomicReference<>(new WorldState(Map.of(), null, null, null, null, initialClock, null,
                    inactive.pause(), null, null, null, null));
            when(session.world(any())).thenAnswer(call -> snapshot.get());
            doAnswer(call -> {
                ModeRequestDTO body = call.getArgument(0);
                if (body.state == ModeState.INACTIVE) {
                    snapshot.set(inactive);
                } else if (failure == -1) {
                    throw new IOException("Connection closed");
                } else if (failure == 400) {
                    throw new ControllerResponseException(400);
                } else {
                    // Simulate a controller adjusting the requested five minutes to one hour.
                    snapshot.set(new WorldState(Map.of(), null, null, null, null, correctedClock, null,
                            new WorldState.Mode(ModeState.ACTIVE, null, now.plusSeconds(3000), null), null, null, null,
                            null));
                }
                return null;
            }).when(session).pause(any());
            var handler = new ControllerHandler(bridge, store, mock(DanfossLinkStateDescriptionProvider.class),
                    java.time.Clock.fixed(now, java.time.ZoneOffset.UTC)) {
                @Override
                CloudClient connect(Identity credentials, Operation operation) {
                    return client;
                }
            };
            var callback = mock(ThingHandlerCallback.class);
            var reports = new AtomicInteger();
            var finished = new CountDownLatch(1);
            var pause = new ChannelUID(bridge.getUID(), CHANNEL_PAUSE);
            var until = new ChannelUID(bridge.getUID(), CHANNEL_PAUSE_UNTIL);
            doAnswer(call -> {
                ThingStatusInfo status = call.getArgument(1);
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
                            handler.handleCommand(until, new DateTimeType(now.plusSeconds(300)));
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
                var bodies = ArgumentCaptor.forClass(ModeRequestDTO.class);
                verify(session, times(2)).pause(bodies.capture());
                assertEquals(now.minusSeconds(300), Objects.requireNonNull(bodies.getAllValues().get(0).endTime)
                        .toInstant(java.time.ZoneOffset.UTC));
                assertEquals(ModeState.INACTIVE, bodies.getAllValues().get(1).state);
                verify(callback, never()).stateUpdated(until, new DateTimeType(now.plusSeconds(300)));
                if (failure == 0) {
                    verify(callback, atLeastOnce()).stateUpdated(until, new DateTimeType(now.plusSeconds(3600)));
                }
                verify(callback, atLeastOnce()).stateUpdated(pause, OnOffType.OFF);
                verify(callback, atLeastOnce()).stateUpdated(until, UnDefType.UNDEF);
                if (failure == -1) {
                    verify(callback, atLeastOnce()).stateUpdated(pause, UnDefType.UNDEF);
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
                    "systemStatus":{"currentState":"AtHome"},
                    "modes":[{"type":"AtHome","state":"Inactive"}]}
                    """).getAsJsonObject();
            when(session.world(any())).thenAnswer(call -> WorldState.parse(json));
            var writes = new AtomicInteger();
            doAnswer(call -> {
                if (writes.getAndIncrement() == 0) {
                    if (failure == 400) {
                        throw new ControllerResponseException(400);
                    } else if (failure == -1) {
                        throw new java.io.IOException("Connection closed");
                    }
                }
                ModeRequestDTO body = call.getArgument(0);
                json.getAsJsonArray("modes").get(0).getAsJsonObject().addProperty("state", body.state.toString());
                return null;
            }).when(session).home(any());
            var handler = new ControllerHandler(bridge, store, mock(DanfossLinkStateDescriptionProvider.class)) {
                @Override
                CloudClient connect(Identity credentials, Operation operation) {
                    return client;
                }
            };
            var callback = mock(ThingHandlerCallback.class);
            var reports = new AtomicInteger();
            var finished = new CountDownLatch(1);
            var channel = new ChannelUID(bridge.getUID(), CHANNEL_HOME);
            var currentStateChannel = new ChannelUID(bridge.getUID(), CHANNEL_CURRENT_STATE);
            doAnswer(call -> {
                ThingStatusInfo status = call.getArgument(1);
                if (status.getStatus() == ThingStatus.OFFLINE && failure == -1) {
                    handler.livingZoneCommand(CHANNEL_MANUAL, OnOffType.OFF);
                }
                if (status.getStatus() == ThingStatus.ONLINE) {
                    switch (reports.incrementAndGet()) {
                        case 1 -> {
                            verify(callback).stateUpdated(currentStateChannel, new StringType("AtHome"));
                            verify(callback).stateUpdated(channel, OnOffType.OFF);
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
                var bodies = ArgumentCaptor.forClass(ModeRequestDTO.class);
                verify(session, times(4)).home(bodies.capture());
                assertEquals(List.of(0), bodies.getAllValues().get(0).rooms);
                assertEquals(List.of(1, 2), bodies.getAllValues().get(1).rooms);
                assertEquals(ModeState.INACTIVE, bodies.getAllValues().get(2).state);
                assertNull(bodies.getAllValues().get(2).rooms);
                assertEquals(List.of(1, 2), bodies.getAllValues().get(3).rooms);
                verify(callback, times(5)).stateUpdated(currentStateChannel, new StringType("AtHome"));
                verify(callback, atLeastOnce()).stateUpdated(channel, OnOffType.ON);
                verify(callback, atLeastOnce()).stateUpdated(channel, OnOffType.OFF);
                if (failure == -1) {
                    verify(callback, atLeastOnce()).stateUpdated(channel, UnDefType.UNDEF);
                    verify(callback).stateUpdated(currentStateChannel, UnDefType.UNDEF);
                }
                verify(session, never()).pause(any());
                verify(session, never()).vacation(any());
            } finally {
                handler.dispose();
            }
        }
    }

    @Test
    void configuredTimeZoneOverridesDefaultAndInvalidConfigurationCanRecover() throws Exception {
        for (String configured : new String[] { "", "   ", "Europe/Copenhagen" }) {
            var configuration = new org.openhab.core.config.core.Configuration(Map.of("timeZone", "Invalid/Zone"));
            var bridge = BridgeBuilder.create(THING_TYPE_CONTROLLER, "zone-test").withConfiguration(configuration)
                    .build();
            var identity = Identity.generate();
            var store = mock(PairingStore.class);
            when(store.load(anyString())).thenReturn(new PairingStore.Credentials(identity, identity.certificate));
            var client = mock(CloudClient.class);
            var session = mock(CloudClient.Session.class);
            when(client.open(any())).thenReturn(session);
            when(session.world(any())).thenReturn(
                    new WorldState(Map.of(), null, null, null, null, null, null, null, null, null, null, null));
            var handler = new ControllerHandler(bridge, store, mock(DanfossLinkStateDescriptionProvider.class),
                    () -> java.time.ZoneId.of("Europe/London")) {
                void configureTimeZone(String zone) {
                    updateConfiguration(new org.openhab.core.config.core.Configuration(Map.of("timeZone", zone)));
                }

                @Override
                CloudClient connect(Identity credentials, Operation operation) {
                    return client;
                }
            };
            var callback = mock(ThingHandlerCallback.class);
            handler.setCallback(callback);
            try {
                handler.initialize();
                var capturedStatus = ArgumentCaptor.forClass(ThingStatusInfo.class);
                verify(callback).statusUpdated(eq(bridge), capturedStatus.capture());
                assertEquals(ThingStatus.OFFLINE, capturedStatus.getValue().getStatus());
                assertEquals(ThingStatusDetail.CONFIGURATION_ERROR, capturedStatus.getValue().getStatusDetail());
                verifyNoInteractions(client);
                handler.dispose();
                handler.configureTimeZone(configured);
                var online = new CountDownLatch(1);
                doAnswer(call -> {
                    ThingStatusInfo status = call.getArgument(1);
                    if (status.getStatus() == ThingStatus.ONLINE) {
                        online.countDown();
                    }
                    return null;
                }).when(callback).statusUpdated(eq(bridge), any());
                handler.initialize();
                assertTrue(online.await(10, TimeUnit.SECONDS));
                var expected = java.time.ZoneId.of(configured.isBlank() ? "Europe/London" : configured);
                verify(session).world(eq(expected));
            } finally {
                handler.dispose();
            }
        }
    }

    @Test
    void vacationTemperatureWritesFreshSettingsAndPublishesReadback() throws Exception {
        var bridge = BridgeBuilder.create(THING_TYPE_CONTROLLER, "temperature").build();
        var identity = Identity.generate();
        var store = mock(PairingStore.class);
        when(store.load(anyString())).thenReturn(new PairingStore.Credentials(identity, identity.certificate));
        var client = mock(CloudClient.class);
        var session = mock(CloudClient.Session.class);
        when(client.open(any())).thenReturn(session);
        var initial = WorldState.parse(JsonParser.parseString("""
                {"allRooms":[],"temperatureSettings":{"temperatureScale":"Celcius",
                "vacationTemperature":{"setpoint":15,"minSetpoint":5,"maxSetpoint":35}}}
                """).getAsJsonObject());
        var updated = WorldState.parse(JsonParser.parseString("""
                {"allRooms":[],"temperatureSettings":{"temperatureScale":"Celcius",
                "vacationTemperature":{"setpoint":16,"minSetpoint":5,"maxSetpoint":35}}}
                """).getAsJsonObject());
        when(session.world(any())).thenReturn(initial, initial, updated);
        var descriptions = mock(DanfossLinkStateDescriptionProvider.class);
        var handler = new ControllerHandler(bridge, store, descriptions) {
            @Override
            CloudClient connect(Identity credentials, Operation operation) {
                return client;
            }
        };
        var callback = mock(ThingHandlerCallback.class);
        var finished = new CountDownLatch(1);
        var queued = new AtomicBoolean();
        doAnswer(call -> {
            ThingStatusInfo status = call.getArgument(1);
            if (status.getStatus() == ThingStatus.ONLINE) {
                if (queued.compareAndSet(false, true)) {
                    handler.handleCommand(new ChannelUID(bridge.getUID(), CHANNEL_VACATION_TEMPERATURE),
                            new org.openhab.core.library.types.DecimalType(16));
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
            var patch = ArgumentCaptor.forClass(WorldState.Patch.class);
            verify(session).apply(patch.capture());
            assertEquals("/api/settings/temperature", patch.getValue().uri());
            assertEquals(JsonParser.parseString("{\"vacationTemperature\":{\"setpoint\":16}}"),
                    patch.getValue().body());
            verify(callback).stateUpdated(new ChannelUID(bridge.getUID(), CHANNEL_VACATION_TEMPERATURE),
                    new org.openhab.core.library.types.QuantityType<>("16 °C"));
            verify(session, never()).vacation(any());
            verify(descriptions, atLeastOnce()).update(
                    eq(new ChannelUID(bridge.getUID(), CHANNEL_VACATION_TEMPERATURE)),
                    eq(new java.math.BigDecimal("5")), eq(new java.math.BigDecimal("35")),
                    eq(org.openhab.core.library.unit.SIUnits.CELSIUS));
        } finally {
            handler.dispose();
        }
        verify(descriptions).remove(bridge.getUID());
    }

    @Test
    void mixedBatchPreservesModePriorityAndReadbackBoundaries() throws Exception {
        var bridge = BridgeBuilder.create(THING_TYPE_CONTROLLER, "mixed").build();
        var identity = Identity.generate();
        var store = mock(PairingStore.class);
        when(store.load(anyString())).thenReturn(new PairingStore.Credentials(identity, identity.certificate));
        var client = mock(CloudClient.class);
        var session = mock(CloudClient.Session.class);
        when(client.open(any())).thenReturn(session);
        when(session.world(any())).thenReturn(WorldState.parse(JsonParser.parseString("""
                {"allRooms":[{"id":1,"zone":"None","minSetpoint":5,"maxSetpoint":35}],
                "temperatureSettings":{"temperatureScale":"Celcius"},
                "modes":[{"type":"Vacation","state":"Inactive"},{"type":"Pause","state":"Inactive"},
                {"type":"AtHome","state":"Inactive"}]}
                """).getAsJsonObject()));
        var handler = new ControllerHandler(bridge, store, mock(DanfossLinkStateDescriptionProvider.class)) {
            @Override
            CloudClient connect(Identity credentials, Operation operation) {
                return client;
            }
        };
        var callback = mock(ThingHandlerCallback.class);
        var queued = new AtomicBoolean();
        var finished = new CountDownLatch(1);
        doAnswer(call -> {
            ThingStatusInfo status = call.getArgument(1);
            if (status.getStatus() == ThingStatus.ONLINE) {
                if (queued.compareAndSet(false, true)) {
                    handler.roomCommand(1, CHANNEL_SETPOINT, new org.openhab.core.library.types.DecimalType(20));
                    handler.handleCommand(new ChannelUID(bridge.getUID(), CHANNEL_HOME), OnOffType.OFF);
                    handler.handleCommand(new ChannelUID(bridge.getUID(), CHANNEL_PAUSE), OnOffType.OFF);
                    handler.handleCommand(new ChannelUID(bridge.getUID(), CHANNEL_VACATION), OnOffType.OFF);
                    handler.roomCommand(1, CHANNEL_HOME_SETPOINT, new org.openhab.core.library.types.DecimalType(21));
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
            var order = inOrder(session);
            order.verify(session).world(any());
            order.verify(session).world(any());
            order.verify(session).vacation(any());
            order.verify(session).world(any());
            order.verify(session).pause(any());
            order.verify(session).world(any());
            order.verify(session).home(any());
            order.verify(session).world(any());
            order.verify(session, times(2)).apply(any());
            order.verify(session).world(any());
            verify(session, times(6)).world(any());
        } finally {
            handler.dispose();
        }
    }
}
