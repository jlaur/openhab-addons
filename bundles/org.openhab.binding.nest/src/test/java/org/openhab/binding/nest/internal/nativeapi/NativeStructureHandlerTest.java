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

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.junit.jupiter.api.Test;
import org.openhab.binding.nest.internal.nativeapi.dto.NativeDtos.Structure;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.ThingStatusInfo;
import org.openhab.core.thing.binding.ThingHandlerCallback;
import org.openhab.core.thing.binding.builder.ThingBuilder;
import org.openhab.core.types.RefreshType;
import org.openhab.core.types.UnDefType;

/**
 * Tests structure observations, command routing, and lifecycle boundaries.
 *
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
class NativeStructureHandlerTest {
    @Test
    void observedStateAndCommandsRemainSeparate() {
        var thing = ThingBuilder.create(NativeThingHandlerFactory.STRUCTURE, "home")
                .withConfiguration(new Configuration(Map.of("structureId", "id"))).build();
        var bridge = mock(Bridge.class);
        var account = mock(NativeAccountHandler.class);
        var callback = mock(ThingHandlerCallback.class);
        when(bridge.getStatus()).thenReturn(ThingStatus.ONLINE);
        when(bridge.getHandler()).thenReturn(account);
        var structure = new Structure();
        structure.name = "Home";
        when(account.structures()).thenReturn(Map.of("id", structure));
        var handler = new NativeStructureHandler(thing) {
            @Override
            protected @Nullable Bridge getBridge() {
                return bridge;
            }
        };
        handler.setCallback(callback);
        var channel = new ChannelUID(thing.getUID(), "away");
        handler.initialize();
        verify(callback).stateUpdated(channel, UnDefType.UNDEF);
        structure.away = false;
        handler.refreshFromBridge();
        verify(callback).stateUpdated(channel, OnOffType.OFF);
        clearInvocations(callback);
        when(account.setAway("id", true)).thenReturn(true);
        when(account.setAway("id", false)).thenReturn(true);
        handler.handleCommand(channel, OnOffType.ON);
        handler.handleCommand(channel, OnOffType.OFF);
        verify(account).setAway("id", true);
        verify(account).setAway("id", false);
        verify(callback, never()).stateUpdated(any(), any());
        handler.handleCommand(channel, RefreshType.REFRESH);
        verify(account).requestRefresh();
        handler.handleCommand(channel, new StringType("invalid"));
        handler.handleCommand(new ChannelUID(thing.getUID(), "unknown"), OnOffType.ON);
        verify(account, times(2)).setAway(anyString(), anyBoolean());
        structure.away = true;
        handler.refreshFromBridge();
        verify(callback).stateUpdated(channel, OnOffType.ON);
        handler.dispose();
        clearInvocations(callback, account);
        handler.handleCommand(channel, OnOffType.ON);
        handler.refreshFromBridge();
        verifyNoInteractions(callback, account);
    }

    @Test
    void unavailableStructureAndBridgeRecoverWithoutInventingState() {
        var thing = ThingBuilder.create(NativeThingHandlerFactory.STRUCTURE, "home")
                .withConfiguration(new Configuration(Map.of("structureId", "id"))).build();
        var bridge = mock(Bridge.class);
        var account = mock(NativeAccountHandler.class);
        var callback = mock(ThingHandlerCallback.class);
        when(bridge.getStatus()).thenReturn(ThingStatus.OFFLINE);
        var handler = new NativeStructureHandler(thing) {
            @Override
            protected @Nullable Bridge getBridge() {
                return bridge;
            }
        };
        handler.setCallback(callback);
        handler.initialize();
        verify(callback).statusUpdated(eq(thing),
                argThat(s -> s.getStatusDetail() == ThingStatusDetail.BRIDGE_OFFLINE));
        when(bridge.getStatus()).thenReturn(ThingStatus.ONLINE);
        when(bridge.getHandler()).thenReturn(account);
        when(account.structures()).thenReturn(Map.of());
        handler.refreshFromBridge();
        verify(callback).statusUpdated(eq(thing),
                argThat(s -> s.getStatusDetail() == ThingStatusDetail.COMMUNICATION_ERROR));
        verify(callback, never()).stateUpdated(any(), any());
        when(account.structures()).thenReturn(Map.of("id", new Structure()));
        handler.bridgeStatusChanged(new ThingStatusInfo(ThingStatus.ONLINE, ThingStatusDetail.NONE, null));
        verify(callback).statusUpdated(eq(thing), argThat(s -> s.getStatus() == ThingStatus.ONLINE));
        verify(callback).stateUpdated(new ChannelUID(thing.getUID(), "away"), UnDefType.UNDEF);
        handler.dispose();
    }
}
