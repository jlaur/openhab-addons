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

import java.math.BigDecimal;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.binding.danfosslink.internal.DanfossLinkStateDescriptionProvider;
import org.openhab.binding.danfosslink.internal.WorldState;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.unit.SIUnits;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.ThingStatusInfo;
import org.openhab.core.thing.binding.ThingHandlerCallback;
import org.openhab.core.thing.binding.builder.ThingBuilder;
import org.openhab.core.types.UnDefType;

import com.google.gson.JsonParser;

/**
 * Verifies publication and invalidation of room regulation settings.
 *
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
class RoomHandlerTest {
    @Test
    void regulationSettingsPublishAndBecomeUndefinedWhenUnavailable() throws Exception {
        var thing = ThingBuilder.create(THING_TYPE_ROOM, "test")
                .withConfiguration(new Configuration(Map.of(PARAMETER_ROOM_ID, BigDecimal.ZERO))).build();
        var callback = mock(ThingHandlerCallback.class);
        var descriptions = mock(DanfossLinkStateDescriptionProvider.class);
        var handler = new RoomHandler(thing, descriptions);
        handler.setCallback(callback);
        var json = JsonParser.parseString("""
                {"temperatureSettings":{"temperatureScale":"Celcius"},"allRooms":[
                {"id":0,"regulation":{"type":"Manual","atHomeSetpoint":22,
                "awaySetpoint":17,"manualSetpoint":20}}]}
                """).getAsJsonObject();
        try {
            handler.initialize();
            handler.publish(WorldState.parse(json));
            verify(callback).stateUpdated(new ChannelUID(thing.getUID(), CHANNEL_HOME_SETPOINT),
                    new QuantityType<>(22, SIUnits.CELSIUS));
            verify(callback).stateUpdated(new ChannelUID(thing.getUID(), CHANNEL_AWAY_SETPOINT),
                    new QuantityType<>(17, SIUnits.CELSIUS));
            verify(callback).stateUpdated(new ChannelUID(thing.getUID(), CHANNEL_MANUAL_SETPOINT),
                    new QuantityType<>(20, SIUnits.CELSIUS));
            verify(callback).stateUpdated(new ChannelUID(thing.getUID(), CHANNEL_MANUAL), OnOffType.ON);
            var regulation = json.getAsJsonArray("allRooms").get(0).getAsJsonObject().getAsJsonObject("regulation");
            regulation.addProperty("type", "Schedule");
            handler.publish(WorldState.parse(json));
            verify(callback).stateUpdated(new ChannelUID(thing.getUID(), CHANNEL_MANUAL), OnOffType.OFF);
            clearInvocations(callback);
            regulation.addProperty("type", "Unexpected");
            handler.publish(WorldState.parse(json));
            verify(callback).stateUpdated(new ChannelUID(thing.getUID(), CHANNEL_MANUAL), UnDefType.UNDEF);
            clearInvocations(callback);
            handler.bridgeStatusChanged(new ThingStatusInfo(ThingStatus.OFFLINE, ThingStatusDetail.NONE, null));
            for (String channel : new String[] { CHANNEL_HOME_SETPOINT, CHANNEL_AWAY_SETPOINT, CHANNEL_MANUAL_SETPOINT,
                    CHANNEL_MANUAL }) {
                verify(callback).stateUpdated(new ChannelUID(thing.getUID(), channel), UnDefType.UNDEF);
            }
            clearInvocations(callback);
            handler.publish(new WorldState(Map.of(), SIUnits.CELSIUS, null, null, null, null, null));
            for (String channel : new String[] { CHANNEL_HOME_SETPOINT, CHANNEL_AWAY_SETPOINT, CHANNEL_MANUAL_SETPOINT,
                    CHANNEL_MANUAL }) {
                verify(callback).stateUpdated(new ChannelUID(thing.getUID(), channel), UnDefType.UNDEF);
            }
            handler.dispose();
            verify(descriptions).remove(thing.getUID());
            clearInvocations(callback, descriptions);
            handler.publish(WorldState.parse(json));
            verifyNoInteractions(callback, descriptions);
        } finally {
            handler.dispose();
        }
    }

    @Test
    void livingZonePropertyTracksReportedMembership() throws Exception {
        var thing = ThingBuilder.create(THING_TYPE_ROOM, "test")
                .withConfiguration(new Configuration(Map.of(PARAMETER_ROOM_ID, BigDecimal.ZERO))).build();
        var callback = mock(ThingHandlerCallback.class);
        var handler = new RoomHandler(thing, mock(DanfossLinkStateDescriptionProvider.class));
        handler.setCallback(callback);
        var json = JsonParser.parseString("{\"allRooms\":[{\"id\":0}]}").getAsJsonObject();
        var room = json.getAsJsonArray("allRooms").get(0).getAsJsonObject();
        try {
            handler.initialize();
            handler.publish(WorldState.parse(json));
            assertNull(thing.getProperties().get("livingZone"));
            verify(callback, never()).thingUpdated(any());
            room.addProperty("zone", "Living");
            handler.publish(WorldState.parse(json));
            assertEquals("true", thing.getProperties().get("livingZone"));
            clearInvocations(callback);
            handler.publish(WorldState.parse(json));
            verify(callback, never()).thingUpdated(any());
            handler.bridgeStatusChanged(new ThingStatusInfo(ThingStatus.OFFLINE, ThingStatusDetail.NONE, null));
            assertEquals("true", thing.getProperties().get("livingZone"));
            room.addProperty("zone", "None");
            handler.publish(WorldState.parse(json));
            assertEquals("false", thing.getProperties().get("livingZone"));
            room.addProperty("zone", "Unexpected");
            handler.publish(WorldState.parse(json));
            assertNull(thing.getProperties().get("livingZone"));
            room.addProperty("zone", "Living");
            handler.publish(WorldState.parse(json));
            room.remove("zone");
            handler.publish(WorldState.parse(json));
            assertNull(thing.getProperties().get("livingZone"));
            handler.dispose();
            clearInvocations(callback);
            room.addProperty("zone", "Living");
            handler.publish(WorldState.parse(json));
            assertNull(thing.getProperties().get("livingZone"));
            verifyNoInteractions(callback);
        } finally {
            handler.dispose();
        }
    }
}
