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
import org.openhab.binding.nest.internal.nativeapi.dto.NativeDtos.Protect;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.binding.ThingHandlerCallback;
import org.openhab.core.thing.binding.builder.ThingBuilder;
import org.openhab.core.types.RefreshType;
import org.openhab.core.types.UnDefType;

/**
 * Tests Pathlight channel mapping and command routing without predicting device state.
 *
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
class NativeProtectHandlerTest {
    @Test
    void pathlightChannelUsesReportedModeAndAcceptsOnlyModeStrings() {
        var thing = ThingBuilder.create(NativeThingHandlerFactory.PROTECT, "protect")
                .withConfiguration(new Configuration(Map.of("deviceId", "id"))).build();
        var bridge = mock(Bridge.class);
        var account = mock(NativeAccountHandler.class);
        var callback = mock(ThingHandlerCallback.class);
        when(bridge.getStatus()).thenReturn(ThingStatus.ONLINE);
        when(bridge.getHandler()).thenReturn(account);
        var protect = new Protect();
        when(account.devices()).thenReturn(Map.of("id", protect));
        var handler = new NativeProtectHandler(thing) {
            @Override
            protected @Nullable Bridge getBridge() {
                return bridge;
            }
        };
        handler.setCallback(callback);
        var channel = new ChannelUID(thing.getUID(), "pathlight-mode");
        handler.initialize();
        verify(callback).stateUpdated(channel, UnDefType.UNDEF);
        protect.nightLightEnabled = false;
        protect.nightLightContinuous = false;
        handler.refreshFromBridge();
        verify(callback).stateUpdated(channel, new StringType("OFF"));
        clearInvocations(callback);
        when(account.setPathlight("id", NativePathlightMode.ON)).thenReturn(true);
        handler.handleCommand(channel, new StringType("ON"));
        verify(account).setPathlight("id", NativePathlightMode.ON);
        verify(callback, never()).stateUpdated(any(), any());
        handler.handleCommand(channel, new StringType("invalid"));
        handler.handleCommand(channel, OnOffType.ON);
        handler.handleCommand(new ChannelUID(thing.getUID(), "smoke-status"), new StringType("ON"));
        verify(account, times(1)).setPathlight(anyString(), any());
        handler.handleCommand(channel, RefreshType.REFRESH);
        verify(account).requestRefresh();
        protect.nightLightEnabled = true;
        handler.refreshFromBridge();
        verify(callback).stateUpdated(channel, new StringType("ON"));
        protect.nightLightContinuous = true;
        handler.refreshFromBridge();
        verify(callback).stateUpdated(channel, new StringType("ALWAYS_ON"));
        var brightness = new ChannelUID(thing.getUID(), "pathlight-brightness");
        clearInvocations(callback);
        handler.refreshFromBridge();
        verify(callback).stateUpdated(brightness, UnDefType.UNDEF);
        for (var level : NativePathlightBrightness.values()) {
            when(account.setPathlightBrightness("id", level)).thenReturn(true);
            clearInvocations(callback);
            handler.handleCommand(brightness, new StringType(level.name()));
            verify(account).setPathlightBrightness("id", level);
            verify(callback, never()).stateUpdated(any(), any());
            protect.nightLightBrightness = level.value;
            handler.refreshFromBridge();
            verify(callback).stateUpdated(brightness, new StringType(level.name()));
        }
        handler.handleCommand(brightness, new StringType("invalid"));
        handler.handleCommand(brightness, OnOffType.ON);
        verify(account, times(3)).setPathlightBrightness(anyString(), any());
        var nightlyPromise = new ChannelUID(thing.getUID(), "nightly-promise");
        clearInvocations(callback);
        handler.refreshFromBridge();
        verify(callback).stateUpdated(nightlyPromise, UnDefType.UNDEF);
        for (var command : OnOffType.values()) {
            boolean enabled = command == OnOffType.ON;
            when(account.setNightlyPromise("id", enabled)).thenReturn(true);
            clearInvocations(callback);
            handler.handleCommand(nightlyPromise, command);
            verify(account).setNightlyPromise("id", enabled);
            verify(callback, never()).stateUpdated(any(), any());
            protect.nightlyPromiseEnabled = enabled;
            handler.refreshFromBridge();
            verify(callback).stateUpdated(nightlyPromise, command);
        }
        handler.handleCommand(nightlyPromise, new StringType("ON"));
        verify(account, times(2)).setNightlyPromise(anyString(), anyBoolean());
        clearInvocations(account);
        handler.handleCommand(nightlyPromise, RefreshType.REFRESH);
        verify(account).requestRefresh();
        verify(account, never()).setNightlyPromise(anyString(), anyBoolean());
        handler.dispose();
        clearInvocations(callback, account);
        handler.handleCommand(nightlyPromise, OnOffType.ON);
        handler.handleCommand(channel, new StringType("OFF"));
        handler.handleCommand(channel, RefreshType.REFRESH);
        handler.refreshFromBridge();
        verifyNoInteractions(callback, account);
    }
}
