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

import static org.openhab.binding.danfosslink.internal.DanfossLinkBindingConstants.*;

import java.time.Instant;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.danfosslink.internal.WorldState;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.ThingStatusInfo;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.openhab.core.types.UnDefType;

/**
 * Controls shared regulation for the controller's built-in Living zone.
 *
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
public class LivingZoneHandler extends BaseThingHandler {
    private final Object lifecycle = new Object();
    private final ScheduleDisplay schedule = new ScheduleDisplay(lifecycle,
            state -> updateState(CHANNEL_SCHEDULE, state));
    private boolean running;

    public LivingZoneHandler(Thing thing) {
        super(thing);
    }

    @Override
    public void initialize() {
        Bridge bridge = getBridge();
        if (bridge == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "Controller Bridge is required");
            return;
        }
        synchronized (lifecycle) {
            running = true;
            schedule.start();
        }
        bridgeStatusChanged(bridge.getStatusInfo());
        if (bridge.getHandler() instanceof ControllerHandler controller) {
            controller.childHandlerInitialized(this, getThing());
            controller.refresh();
        }
    }

    @Override
    public void dispose() {
        synchronized (lifecycle) {
            running = false;
            schedule.stop();
        }
    }

    @Override
    public void handleCommand(ChannelUID channel, Command command) {
        synchronized (lifecycle) {
            if (!running) {
                return;
            }
        }
        var bridge = getBridge();
        if (bridge != null && bridge.getHandler() instanceof ControllerHandler controller) {
            if (command == RefreshType.REFRESH) {
                if (CHANNEL_SCHEDULE.equals(channel.getId())) {
                    schedule.invalidate();
                    controller.refreshSchedules();
                } else {
                    controller.refresh();
                }
            } else if (CHANNEL_HOME_SELECTED.equals(channel.getId())) {
                controller.selectHome(null, command);
            } else if (CHANNEL_MANUAL.equals(channel.getId())) {
                controller.livingZoneCommand(channel.getId(), command);
            }
        }
    }

    void invalidateSchedule() {
        schedule.invalidate();
    }

    ScheduleDisplay.@Nullable Request scheduleRequest(WorldState snapshot, Instant now) {
        synchronized (lifecycle) {
            return schedule.request(ScheduleDisplay.source(snapshot, null), isLinked(CHANNEL_SCHEDULE), now);
        }
    }

    @Override
    public void channelLinked(ChannelUID channel) {
        if (CHANNEL_SCHEDULE.equals(channel.getId())) {
            schedule.invalidate();
            var bridge = getBridge();
            if (bridge != null && bridge.getHandler() instanceof ControllerHandler controller) {
                controller.refreshSchedules();
            }
        } else {
            super.channelLinked(channel);
        }
    }

    @Override
    public void channelUnlinked(ChannelUID channel) {
        if (CHANNEL_SCHEDULE.equals(channel.getId())) {
            schedule.invalidate();
        }
    }

    @Override
    public void bridgeStatusChanged(ThingStatusInfo status) {
        synchronized (lifecycle) {
            if (!running) {
                return;
            }
            if (status.getStatus() != ThingStatus.ONLINE) {
                updateState(CHANNEL_MANUAL, UnDefType.UNDEF);
                schedule.invalidate();
                updateState(CHANNEL_SCHEDULE, UnDefType.UNDEF);
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_OFFLINE);
            } else if (getThing().getStatus() != ThingStatus.ONLINE) {
                updateStatus(ThingStatus.UNKNOWN);
            }
        }
    }

    void publishHomeSelection(HomeSelection selection, WorldState snapshot) {
        synchronized (lifecycle) {
            if (running) {
                updateState(CHANNEL_HOME_SELECTED, selection.state(null, snapshot));
            }
        }
    }

    void publish(WorldState snapshot) {
        synchronized (lifecycle) {
            if (running) {
                // The world response reports regulation on member rooms, not a separate zone object.
                updateState(CHANNEL_MANUAL, snapshot.livingZoneManualState());
                updateStatus(ThingStatus.ONLINE);
            }
        }
    }
}
