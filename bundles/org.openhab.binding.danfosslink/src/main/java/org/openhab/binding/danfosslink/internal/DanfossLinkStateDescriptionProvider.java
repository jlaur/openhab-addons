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

import static org.openhab.binding.danfosslink.internal.DanfossLinkBindingConstants.*;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import javax.measure.Unit;
import javax.measure.quantity.Temperature;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.events.EventPublisher;
import org.openhab.core.thing.Channel;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.binding.BaseDynamicStateDescriptionProvider;
import org.openhab.core.thing.events.ThingEventFactory;
import org.openhab.core.thing.link.ItemChannelLinkRegistry;
import org.openhab.core.thing.type.DynamicStateDescriptionProvider;
import org.openhab.core.types.StateDescription;
import org.openhab.core.types.StateDescriptionFragment;
import org.openhab.core.types.StateDescriptionFragmentBuilder;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;

/**
 * Supplies the controller's room-specific temperature limits to setpoint controls.
 *
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
@Component(service = { DynamicStateDescriptionProvider.class, DanfossLinkStateDescriptionProvider.class })
public class DanfossLinkStateDescriptionProvider extends BaseDynamicStateDescriptionProvider {
    private final Map<ChannelUID, StateDescriptionFragment> descriptionFragments = new ConcurrentHashMap<>();
    private final ItemChannelLinkRegistry itemChannelLinkRegistry;

    @Activate
    public DanfossLinkStateDescriptionProvider(@Reference EventPublisher eventPublisher,
            @Reference ItemChannelLinkRegistry itemChannelLinkRegistry) {
        this.eventPublisher = eventPublisher;
        this.itemChannelLinkRegistry = itemChannelLinkRegistry;
    }

    public void update(ThingUID thing, @Nullable BigDecimal minimum, @Nullable BigDecimal maximum,
            @Nullable Unit<Temperature> unit) {
        StateDescriptionFragment stateDescription = null;
        if (minimum != null && maximum != null && unit != null && minimum.compareTo(maximum) <= 0) {
            // State-description bounds have no unit of their own; keep the display pattern in the same scale.
            stateDescription = StateDescriptionFragmentBuilder.create().withMinimum(minimum.stripTrailingZeros())
                    .withMaximum(maximum.stripTrailingZeros()).withPattern("%.1f " + unit).build();
        }
        for (String channelId : new String[] { CHANNEL_SETPOINT, CHANNEL_HOME_SETPOINT, CHANNEL_AWAY_SETPOINT,
                CHANNEL_MANUAL_SETPOINT }) {
            var channelUid = new ChannelUID(thing, channelId);
            var previous = stateDescription == null ? descriptionFragments.remove(channelUid)
                    : descriptionFragments.put(channelUid, stateDescription);
            if (!Objects.equals(previous, stateDescription)) {
                postEvent(ThingEventFactory.createChannelDescriptionChangedEvent(channelUid,
                        itemChannelLinkRegistry.getLinkedItemNames(channelUid),
                        stateDescription == null ? StateDescriptionFragmentBuilder.create().build() : stateDescription,
                        previous));
            }
        }
    }

    public void remove(ThingUID thing) {
        update(thing, null, null, null);
    }

    @Override
    public @Nullable StateDescription getStateDescription(Channel channel,
            @Nullable StateDescription originalStateDescription, @Nullable Locale locale) {
        StateDescriptionFragment stateDescription = descriptionFragments.get(channel.getUID());
        if (stateDescription == null) {
            return null;
        }
        StateDescriptionFragmentBuilder builder = originalStateDescription == null
                ? StateDescriptionFragmentBuilder.create()
                : StateDescriptionFragmentBuilder.create(originalStateDescription);
        return builder.withMinimum(Objects.requireNonNull(stateDescription.getMinimum()))
                .withMaximum(Objects.requireNonNull(stateDescription.getMaximum()))
                .withPattern(Objects.requireNonNull(stateDescription.getPattern())).build().toStateDescription();
    }

    @Override
    @Deactivate
    public void deactivate() {
        descriptionFragments.clear();
        super.deactivate();
    }
}
