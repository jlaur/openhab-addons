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
package org.openhab.binding.danfosslink.internal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.openhab.binding.danfosslink.internal.DanfossLinkBindingConstants.*;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.core.events.EventPublisher;
import org.openhab.core.library.unit.ImperialUnits;
import org.openhab.core.library.unit.SIUnits;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.binding.builder.ChannelBuilder;
import org.openhab.core.thing.link.ItemChannelLinkRegistry;
import org.openhab.core.types.StateDescriptionFragmentBuilder;

/**
 * Verifies room-specific limits, change notifications, units, and cleanup.
 *
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
class DanfossLinkStateDescriptionProviderTest {
    @Test
    void limitsUpdateWithoutLosingStaticMetadataAndAreRemovedOnDisposal() {
        var events = mock(EventPublisher.class);
        var links = mock(ItemChannelLinkRegistry.class);
        when(links.getLinkedItemNames(any())).thenReturn(Set.of("Setpoint"));
        var provider = new DanfossLinkStateDescriptionProvider(events, links);
        var room = new ThingUID(THING_TYPE_ROOM, "test");
        var original = StateDescriptionFragmentBuilder.create().withPattern("%.1f %unit%")
                .withStep(new BigDecimal("0.5")).withReadOnly(false).build().toStateDescription();
        provider.update(room, new BigDecimal("6.0"), new BigDecimal("35.0"), SIUnits.CELSIUS);
        for (String id : new String[] { CHANNEL_SETPOINT, CHANNEL_HOME_SETPOINT, CHANNEL_AWAY_SETPOINT,
                CHANNEL_MANUAL_SETPOINT }) {
            var channel = ChannelBuilder.create(new ChannelUID(room, id), "Number:Temperature").build();
            var state = Objects.requireNonNull(provider.getStateDescription(channel, original, null));
            assertEquals(new BigDecimal("6"), state.getMinimum());
            assertEquals(new BigDecimal("35"), state.getMaximum());
            assertEquals("%.1f " + SIUnits.CELSIUS, state.getPattern());
            assertEquals(new BigDecimal("0.5"), state.getStep());
            assertFalse(state.isReadOnly());
        }
        verify(events, times(4)).post(any());
        clearInvocations(events);
        provider.update(room, new BigDecimal("6"), new BigDecimal("35"), SIUnits.CELSIUS);
        verifyNoInteractions(events);
        var channel = ChannelBuilder.create(new ChannelUID(room, CHANNEL_SETPOINT), "Number:Temperature").build();
        var other = ChannelBuilder
                .create(new ChannelUID(new ThingUID(THING_TYPE_ROOM, "other"), CHANNEL_SETPOINT), "Number:Temperature")
                .build();
        assertNull(provider.getStateDescription(other, original, null));
        provider.update(room, new BigDecimal("42.8"), new BigDecimal("95"), ImperialUnits.FAHRENHEIT);
        var state = Objects.requireNonNull(provider.getStateDescription(channel, original, null));
        assertEquals(new BigDecimal("42.8"), state.getMinimum());
        assertEquals(new BigDecimal("95"), state.getMaximum());
        assertEquals("%.1f " + ImperialUnits.FAHRENHEIT, state.getPattern());
        verify(events, times(4)).post(any());
        provider.remove(room);
        assertNull(provider.getStateDescription(channel, original, null));
        verify(events, times(8)).post(any());
        provider.update(room, BigDecimal.ONE, BigDecimal.TEN, SIUnits.CELSIUS);
        provider.deactivate();
        assertNull(provider.getStateDescription(channel, original, null));
    }

    @Test
    void missingOrInvalidLimitsNeverPublishAnInventedRange() {
        var provider = new DanfossLinkStateDescriptionProvider(mock(EventPublisher.class),
                mock(ItemChannelLinkRegistry.class));
        var room = new ThingUID(THING_TYPE_ROOM, "test");
        var channel = ChannelBuilder.create(new ChannelUID(room, CHANNEL_SETPOINT), "Number:Temperature").build();
        provider.update(room, BigDecimal.ONE, BigDecimal.TEN, SIUnits.CELSIUS);
        assertNotNull(provider.getStateDescription(channel, null, null));
        provider.update(room, null, BigDecimal.TEN, SIUnits.CELSIUS);
        assertNull(provider.getStateDescription(channel, null, null));
        provider.update(room, BigDecimal.ONE, null, SIUnits.CELSIUS);
        assertNull(provider.getStateDescription(channel, null, null));
        provider.update(room, BigDecimal.ONE, BigDecimal.TEN, null);
        assertNull(provider.getStateDescription(channel, null, null));
        provider.update(room, BigDecimal.TEN, BigDecimal.ONE, SIUnits.CELSIUS);
        assertNull(provider.getStateDescription(channel, null, null));
    }

    @Test
    void controllerVacationLimitsAreIndependentAndRemovedWhenUnavailable() {
        var provider = new DanfossLinkStateDescriptionProvider(mock(EventPublisher.class),
                mock(ItemChannelLinkRegistry.class));
        var controller = new ThingUID(THING_TYPE_CONTROLLER, "test");
        var room = new ThingUID(THING_TYPE_ROOM, "test");
        var channel = ChannelBuilder
                .create(new ChannelUID(controller, CHANNEL_VACATION_TEMPERATURE), "Number:Temperature").build();
        provider.update(room, new BigDecimal("6"), new BigDecimal("28"), SIUnits.CELSIUS);
        provider.update(channel.getUID(), new BigDecimal("5"), new BigDecimal("35"), SIUnits.CELSIUS);
        var state = Objects.requireNonNull(provider.getStateDescription(channel, null, null));
        assertEquals(new BigDecimal("5"), state.getMinimum());
        assertEquals(new BigDecimal("35"), state.getMaximum());
        provider.remove(controller);
        assertNull(provider.getStateDescription(channel, null, null));
        assertNotNull(provider.getStateDescription(
                ChannelBuilder.create(new ChannelUID(room, CHANNEL_SETPOINT), "Number:Temperature").build(), null,
                null));
    }
}
