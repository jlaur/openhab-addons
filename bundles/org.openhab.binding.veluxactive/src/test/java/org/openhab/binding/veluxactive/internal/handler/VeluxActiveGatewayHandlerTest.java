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
package org.openhab.binding.veluxactive.internal.handler;

import static java.util.Objects.requireNonNull;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.openhab.binding.veluxactive.internal.VeluxActiveBindingConstants.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openhab.binding.veluxactive.internal.VeluxActiveApi.ModuleInfo;
import org.openhab.binding.veluxactive.internal.api.dto.HomeStatus;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusInfo;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.binding.ThingHandlerCallback;
import org.openhab.core.thing.binding.builder.BridgeBuilder;
import org.openhab.core.types.RefreshType;
import org.openhab.core.types.State;
import org.openhab.core.types.UnDefType;

import com.google.gson.Gson;

/**
 * Verifies gateway state, command semantics and stale callback suppression.
 *
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
class VeluxActiveGatewayHandlerTest {
    private final ThingUID bridgeUID = new ThingUID(THING_TYPE_ACCOUNT, "test");
    private final Bridge thing = BridgeBuilder.create(THING_TYPE_GATEWAY, "gateway").withBridge(bridgeUID)
            .withConfiguration(new Configuration(Map.of("moduleId", "gateway"))).build();
    private final Bridge bridge = mock(Bridge.class);
    private final VeluxActiveAccountHandler account = mock(VeluxActiveAccountHandler.class);
    private final ThingHandlerCallback callback = mock(ThingHandlerCallback.class);
    private final VeluxActiveGatewayHandler handler = new VeluxActiveGatewayHandler(thing);
    private final Map<String, State> states = new HashMap<>();

    @BeforeEach
    void setup() {
        when(callback.getBridge(bridgeUID)).thenReturn(bridge);
        when(bridge.getHandler()).thenReturn(account);
        when(account.homeId("gateway")).thenReturn("home");
        when(bridge.getStatus()).thenReturn(ThingStatus.ONLINE);
        doAnswer(invocation -> {
            ChannelUID channel = invocation.getArgument(0);
            states.put(channel.getId(), invocation.getArgument(1));
            return null;
        }).when(callback).stateUpdated(any(), any());
        doAnswer(invocation -> {
            ThingStatusInfo status = invocation.getArgument(1);
            thing.setStatusInfo(status);
            return null;
        }).when(callback).statusUpdated(any(), any());
        handler.setCallback(callback);
        handler.initialize();
    }

    @Test
    void publishesOnlyReportedState() {
        update("""
                {"home":{"id":"home","modules":[{"id":"gateway","locked":true,"locking":false,
                "last_seen":1791635715}]}}
                """);
        assertThat(thing.getStatus(), is(ThingStatus.ONLINE));
        assertThat(states.get(CHANNEL_LOCKED), is(OnOffType.ON));
        assertThat(states.get(CHANNEL_LOCKING), is(OnOffType.OFF));
        assertThat(states.get(CHANNEL_LAST_SEEN), not(UnDefType.UNDEF));
        update("{\"home\":{\"id\":\"home\",\"modules\":[{\"id\":\"gateway\"}]}}");
        assertThat(states.get(CHANNEL_LOCKED), is(UnDefType.UNDEF));
        assertThat(states.get(CHANNEL_LOCKING), is(UnDefType.UNDEF));
        assertThat(states.get(CHANNEL_LAST_SEEN), is(UnDefType.UNDEF));
    }

    @Test
    void unreachableErrorOverridesPresentModuleAndRecovers() {
        update("""
                {"home":{"id":"home","modules":[{"id":"gateway","locked":true}]},
                 "errors":[{"id":"gateway","code":6}]}
                """);
        assertThat(thing.getStatus(), is(ThingStatus.OFFLINE));
        assertThat(states.get(CHANNEL_LOCKED), is(UnDefType.UNDEF));
        update("{\"home\":{\"id\":\"home\",\"modules\":[{\"id\":\"gateway\",\"locked\":false}]}}");
        assertThat(thing.getStatus(), is(ThingStatus.ONLINE));
        assertThat(states.get(CHANNEL_LOCKED), is(OnOffType.OFF));
    }

    @Test
    void missingGatewayIsUnknownNotUnlocked() {
        update("{\"home\":{\"id\":\"home\",\"modules\":[]}}");
        assertThat(thing.getStatus(), is(ThingStatus.UNKNOWN));
        assertThat(states.get(CHANNEL_LOCKED), is(UnDefType.UNDEF));
    }

    @Test
    void onlyOnOnLockedChannelSendsCommandWithoutOptimisticState() {
        clearInvocations(account);
        handler.handleCommand(new ChannelUID(thing.getUID(), CHANNEL_LOCKING), OnOffType.ON);
        handler.handleCommand(new ChannelUID(thing.getUID(), CHANNEL_LOCKED), OnOffType.OFF);
        verify(account, never()).lock(any());
        verify(account, never()).requestPoll();
        handler.handleCommand(new ChannelUID(thing.getUID(), CHANNEL_LOCKED), OnOffType.ON);
        verify(account).lock(any());
        assertThat(states.containsKey(CHANNEL_LOCKED), is(false));
        handler.handleCommand(new ChannelUID(thing.getUID(), CHANNEL_LOCKED), RefreshType.REFRESH);
        verify(account).requestPoll();
    }

    @Test
    void disposalSuppressesLateStatusAndCommands() {
        var target = requireNonNull(handler.target());
        handler.dispose();
        clearInvocations(callback, account);
        handler.update(target, status("{\"home\":{\"id\":\"home\",\"modules\":[]}}"));
        handler.failed(target, "late failure");
        handler.lockAccepted(target);
        handler.handleCommand(new ChannelUID(thing.getUID(), CHANNEL_LOCKED), OnOffType.ON);
        verifyNoInteractions(callback, account);
    }

    @Test
    void acknowledgedLockUpdatesChannelAndPollingReconcilesIt() {
        update("""
                {"home":{"id":"home","modules":[{"id":"gateway","locked":false,"locking":false}]}}
                """);
        handler.lockAccepted(requireNonNull(handler.target()));
        assertThat(states.get(CHANNEL_LOCKED), is(OnOffType.ON));
        assertThat(states.get(CHANNEL_LOCKING), is(OnOffType.OFF));
        update("""
                {"home":{"id":"home","modules":[{"id":"gateway","locked":false}]}}
                """);
        assertThat(states.get(CHANNEL_LOCKED), is(OnOffType.OFF));
    }

    @Test
    void publishesFirmwarePropertiesAndRetainsThemAcrossPartialResponses() {
        update("""
                {"home":{"id":"home","modules":[{"id":"gateway","firmware_revision_netatmo":203,
                "firmware_revision_thirdparty":"0.1.2.0.43.0","hardware_version":236}]}}
                """);
        assertThat(thing.getProperties().get(Thing.PROPERTY_FIRMWARE_VERSION), is("203"));
        assertThat(thing.getProperties().get(Thing.PROPERTY_HARDWARE_VERSION), is("236"));
        update("""
                {"home":{"id":"home","modules":[{"id":"gateway","locked":false}]}}
                """);
        assertThat(thing.getProperties().get(Thing.PROPERTY_FIRMWARE_VERSION), is("203"));
    }

    @Test
    void discoveryFiltersByBothHomeAndGateway() {
        doAnswer(invocation -> {
            Consumer<List<ModuleInfo>> consumer = invocation.getArgument(0);
            consumer.accept(List.of(new ModuleInfo("home", "sensor", "Sensor", "NXS", null, null, "gateway"),
                    new ModuleInfo("home", "other", "Other", "NXS", null, null, "other-gateway"),
                    new ModuleInfo("other-home", "foreign", "Foreign", "NXS", null, null, "gateway")));
            return null;
        }).when(account).discover(any());
        var results = new ArrayList<ModuleInfo>();
        handler.discover(results::addAll);
        assertThat(results.size(), is(1));
        assertThat(results.getFirst().moduleId(), is("sensor"));
    }

    private void update(String json) {
        handler.update(requireNonNull(handler.target()), status(json));
    }

    private static HomeStatus status(String json) {
        return requireNonNull(new Gson().fromJson(json, HomeStatus.class));
    }
}
