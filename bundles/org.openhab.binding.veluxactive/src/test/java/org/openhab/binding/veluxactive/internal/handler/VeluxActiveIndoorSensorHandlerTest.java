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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.openhab.binding.veluxactive.internal.VeluxActiveBindingConstants.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openhab.binding.veluxactive.internal.VeluxActiveApi;
import org.openhab.binding.veluxactive.internal.api.dto.HomeStatus;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.unit.SIUnits;
import org.openhab.core.library.unit.Units;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusInfo;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.binding.ThingHandlerCallback;
import org.openhab.core.thing.binding.builder.ThingBuilder;
import org.openhab.core.types.RefreshType;
import org.openhab.core.types.State;
import org.openhab.core.types.UnDefType;

import com.google.gson.Gson;

/**
 * Verifies sensor measurements, room reassignment and lifecycle handling.
 *
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
class VeluxActiveIndoorSensorHandlerTest {
    private final ThingUID bridgeUID = new ThingUID(THING_TYPE_ACCOUNT, "test");
    private final Thing thing = ThingBuilder.create(THING_TYPE_INDOOR_SENSOR, "gateway").withBridge(bridgeUID)
            .withConfiguration(new Configuration(Map.of("homeId", "home", "moduleId", "gateway"))).build();
    private final Bridge bridge = mock(Bridge.class);
    private final VeluxActiveAccountHandler account = mock(VeluxActiveAccountHandler.class);
    private final ThingHandlerCallback callback = mock(ThingHandlerCallback.class);
    private final VeluxActiveIndoorSensorHandler handler = new VeluxActiveIndoorSensorHandler(thing);
    private final Map<String, State> states = new HashMap<>();

    @BeforeEach
    void setup() {
        when(callback.getBridge(bridgeUID)).thenReturn(bridge);
        when(bridge.getHandler()).thenReturn(account);
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
    void combinesRoomMeasurementsWithSensorDiagnostics() {
        topology("hall");
        update("""
                {"home":{"id":"home","modules":[{"id":"gateway","type":"NXS","reachable":true,
                  "battery_percent":56,"battery_state":"medium","rf_state":"full","firmware_revision":16,
                  "last_seen":1791635702}],
                  "rooms":[{"id":"hall","temperature":212,"humidity":50,"co2":715,"lux":12}]}}
                """);
        assertThat(thing.getStatus(), is(ThingStatus.ONLINE));
        assertThat(states.get(CHANNEL_TEMPERATURE), is(new QuantityType<>(21.2, SIUnits.CELSIUS)));
        assertThat(states.get(CHANNEL_HUMIDITY), is(new QuantityType<>(50, Units.PERCENT)));
        assertThat(states.get(CHANNEL_CO2), is(new QuantityType<>(715, Units.PARTS_PER_MILLION)));
        assertThat(states.get(CHANNEL_ILLUMINANCE), is(new QuantityType<>(12, Units.LUX)));
        assertThat(states.get(CHANNEL_BATTERY), is(new DecimalType(56)));
        assertThat(states.get(CHANNEL_LOW_BATTERY), is(OnOffType.OFF));
        assertThat(states.get(CHANNEL_SIGNAL), is(new DecimalType(4)));
        assertThat(thing.getProperties().get(Thing.PROPERTY_FIRMWARE_VERSION), is("16"));
        assertThat(thing.getProperties().get("roomId"), is("hall"));
    }

    @Test
    void reassignmentUsesNewRoomAndMissingValuesAreUndefined() {
        String status = """
                {"home":{"id":"home","modules":[{"id":"gateway","battery_percent":35}],
                  "rooms":[{"id":"hall","temperature":212},{"id":"bedroom","temperature":185}]}}
                """;
        topology("hall");
        update(status);
        topology("bedroom");
        update(status);
        assertThat(states.get(CHANNEL_TEMPERATURE), is(new QuantityType<>(18.5, SIUnits.CELSIUS)));
        assertThat(states.get(CHANNEL_HUMIDITY), is(UnDefType.UNDEF));
        handler.updateTopology(requireNonNull(handler.target()), List.of());
        update(status);
        assertThat(states.get(CHANNEL_TEMPERATURE), is(UnDefType.UNDEF));
        assertThat(states.get(CHANNEL_BATTERY), is(new DecimalType(35)));
    }

    @Test
    void diagnosticsWorkBeforeTopologyIsAvailable() {
        update("""
                {"home":{"id":"home","modules":[{"id":"gateway","battery_percent":56,"battery_state":"low"}],
                 "rooms":[{"id":"hall","temperature":212}]}}
                """);
        assertThat(thing.getStatus(), is(ThingStatus.ONLINE));
        assertThat(states.get(CHANNEL_TEMPERATURE), is(UnDefType.UNDEF));
        assertThat(states.get(CHANNEL_BATTERY), is(new DecimalType(56)));
        assertThat(states.get(CHANNEL_LOW_BATTERY), is(OnOffType.ON));
    }

    @Test
    void unreachableSensorDoesNotPublishRoomClimateAndRecovers() {
        topology("hall");
        update("""
                {"home":{"id":"home","modules":[{"id":"gateway","reachable":false}],
                 "rooms":[{"id":"hall","temperature":212}]}}
                """);
        assertThat(thing.getStatus(), is(ThingStatus.OFFLINE));
        assertThat(states.get(CHANNEL_TEMPERATURE), is(UnDefType.UNDEF));
        update("""
                {"home":{"id":"home","modules":[{"id":"gateway","reachable":true}],
                 "rooms":[{"id":"hall","temperature":212}]}}
                """);
        assertThat(thing.getStatus(), is(ThingStatus.ONLINE));
        assertThat(states.get(CHANNEL_TEMPERATURE), is(new QuantityType<>(21.2, SIUnits.CELSIUS)));
    }

    @Test
    void disposalSuppressesUpdatesAndRefresh() {
        var target = requireNonNull(handler.target());
        handler.dispose();
        clearInvocations(callback, account);
        handler.updateTopology(target, List.of());
        handler.update(target, new HomeStatus());
        handler.failed(target, "late failure");
        handler.handleCommand(new ChannelUID(thing.getUID(), CHANNEL_TEMPERATURE), RefreshType.REFRESH);
        verifyNoInteractions(callback, account);
    }

    @Test
    void refreshRequestsSharedPollAndOtherCommandsAreIgnored() {
        clearInvocations(account);
        handler.handleCommand(new ChannelUID(thing.getUID(), CHANNEL_TEMPERATURE), RefreshType.REFRESH);
        verify(account).requestPoll();
        clearInvocations(account);
        handler.handleCommand(new ChannelUID(thing.getUID(), CHANNEL_TEMPERATURE), OnOffType.ON);
        verifyNoInteractions(account);
    }

    private void topology(String roomId) {
        handler.updateTopology(requireNonNull(handler.target()),
                List.of(new VeluxActiveApi.ModuleInfo("home", "gateway", "Indoor sensor", "NXS", roomId, roomId)));
    }

    private void update(String json) {
        handler.update(requireNonNull(handler.target()), requireNonNull(new Gson().fromJson(json, HomeStatus.class)));
    }
}
