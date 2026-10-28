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

import static org.mockito.Mockito.*;
import static org.openhab.binding.danfosslink.internal.DanfossLinkBindingConstants.*;

import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.binding.danfosslink.internal.WorldState;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.ThingStatusInfo;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.binding.ThingHandlerCallback;
import org.openhab.core.thing.binding.builder.ThingBuilder;
import org.openhab.core.types.RefreshType;
import org.openhab.core.types.UnDefType;

import com.google.gson.JsonParser;

/**
 * Verifies Living-zone command routing, state publication, and lifecycle cleanup.
 *
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
class LivingZoneHandlerTest {
    @Test
    void commandsAndStatesFollowBridgeLifecycle() throws Exception {
        var bridgeUID = new ThingUID(THING_TYPE_CONTROLLER, "test");
        var thing = ThingBuilder.create(THING_TYPE_LIVING_ZONE, "living").withBridge(bridgeUID).build();
        var bridge = mock(Bridge.class);
        var controller = mock(ControllerHandler.class);
        when(bridge.getHandler()).thenReturn(controller);
        when(bridge.getStatusInfo()).thenReturn(new ThingStatusInfo(ThingStatus.ONLINE, ThingStatusDetail.NONE, null));
        var callback = mock(ThingHandlerCallback.class);
        when(callback.getBridge(bridgeUID)).thenReturn(bridge);
        var handler = new LivingZoneHandler(thing);
        handler.setCallback(callback);
        var channel = new ChannelUID(thing.getUID(), CHANNEL_MANUAL);
        var state = WorldState.parse(JsonParser.parseString("""
                {"allRooms":[{"id":0,"zone":"Living","regulation":{"type":"Manual"}}]}
                """).getAsJsonObject());
        try {
            handler.initialize();
            verify(controller).childHandlerInitialized(handler, thing);
            clearInvocations(controller);
            var selected = new ChannelUID(thing.getUID(), CHANNEL_HOME_SELECTED);
            handler.handleCommand(selected, OnOffType.ON);
            verify(controller).selectHome(null, OnOffType.ON);
            var selection = new HomeSelection();
            selection.select(null, OnOffType.ON, state);
            handler.publishHomeSelection(selection, state);
            verify(callback).stateUpdated(selected, OnOffType.ON);
            handler.handleCommand(channel, OnOffType.OFF);
            verify(controller).livingZoneCommand(CHANNEL_MANUAL, OnOffType.OFF);
            handler.handleCommand(channel, RefreshType.REFRESH);
            verify(controller).refresh();
            handler.publish(state);
            verify(callback).stateUpdated(channel, OnOffType.ON);
            clearInvocations(callback);
            handler.bridgeStatusChanged(new ThingStatusInfo(ThingStatus.OFFLINE, ThingStatusDetail.NONE, null));
            verify(callback).stateUpdated(channel, UnDefType.UNDEF);
            handler.publish(state);
            verify(callback).stateUpdated(channel, OnOffType.ON);
            clearInvocations(callback);
            handler.publish(new WorldState(Map.of(), null, null, null, null, null, null, null, null, null, null, null));
            verify(callback).stateUpdated(channel, UnDefType.UNDEF);
            handler.dispose();
            clearInvocations(callback, controller);
            handler.publish(state);
            handler.publishHomeSelection(selection, state);
            handler.handleCommand(selected, OnOffType.ON);
            handler.handleCommand(channel, OnOffType.ON);
            handler.bridgeStatusChanged(new ThingStatusInfo(ThingStatus.OFFLINE, ThingStatusDetail.NONE, null));
            verifyNoInteractions(callback, controller);
            handler.initialize();
            handler.publish(state);
            verify(callback).stateUpdated(channel, OnOffType.ON);
        } finally {
            handler.dispose();
        }
    }
}
