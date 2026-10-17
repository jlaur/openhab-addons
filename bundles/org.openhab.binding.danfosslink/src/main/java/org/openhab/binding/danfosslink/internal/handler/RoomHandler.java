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

import java.math.BigDecimal;
import java.util.Objects;

import javax.measure.Unit;
import javax.measure.quantity.Temperature;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.danfosslink.internal.DanfossLinkStateDescriptionProvider;
import org.openhab.binding.danfosslink.internal.WorldState;
import org.openhab.binding.danfosslink.internal.config.RoomConfiguration;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.ThingStatusInfo;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.openhab.core.types.State;
import org.openhab.core.types.UnDefType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A room's temperatures, regulation settings, and regulation state.
 *
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
public class RoomHandler extends BaseThingHandler {
    private static final String PROPERTY_LIVING_ZONE = "livingZone";

    private final Logger logger = LoggerFactory.getLogger(RoomHandler.class);
    private final Object lifecycle = new Object();
    private final DanfossLinkStateDescriptionProvider stateDescriptionProvider;
    private boolean running;
    private int roomId;

    public RoomHandler(Thing thing, DanfossLinkStateDescriptionProvider stateDescriptionProvider) {
        super(thing);
        this.stateDescriptionProvider = stateDescriptionProvider;
    }

    @Override
    public void initialize() {
        Integer id = getConfigAs(RoomConfiguration.class).roomId;
        if (id == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "Missing room ID");
            return;
        }
        if (id < 0) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "Negative room ID");
            return;
        }

        synchronized (lifecycle) {
            roomId = id;
            running = true;
        }

        Bridge bridge = getBridge();
        if (bridge == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "Controller Bridge is required");
            return;
        }
        bridgeStatusChanged(bridge.getStatusInfo());
        ControllerHandler controller = controller();
        if (controller != null) {
            controller.childHandlerInitialized(this, getThing());
            controller.refresh();
        }
    }

    @Override
    public void dispose() {
        synchronized (lifecycle) {
            running = false;
            stateDescriptionProvider.remove(getThing().getUID());
        }
    }

    private @Nullable ControllerHandler controller() {
        var bridge = getBridge();
        return bridge != null && bridge.getHandler() instanceof ControllerHandler handler ? handler : null;
    }

    @Override
    public void handleCommand(ChannelUID channel, Command command) {
        ControllerHandler controller = controller();
        if (controller == null) {
            logger.debug("Ignoring command for {}: controller handler unavailable", channel);
            return;
        }
        int id;
        synchronized (lifecycle) {
            if (!running) {
                logger.debug("Ignoring command for {}: room handler is not running", channel);
                return;
            }
            id = roomId;
        }
        if (command == RefreshType.REFRESH) {
            controller.refresh();
        } else {
            switch (channel.getId()) {
                case CHANNEL_HOME_SELECTED -> controller.selectHome(id, command);
                case CHANNEL_SETPOINT, CHANNEL_HOME_SETPOINT, CHANNEL_AWAY_SETPOINT, CHANNEL_MANUAL_SETPOINT,
                        CHANNEL_MANUAL ->
                    controller.roomCommand(id, channel.getId(), command);
                default -> {
                }
            }
        }
    }

    @Override
    public void bridgeStatusChanged(ThingStatusInfo status) {
        synchronized (lifecycle) {
            if (!running) {
                return;
            }
            if (status.getStatus() != ThingStatus.ONLINE) {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_OFFLINE);
                updateState(CHANNEL_TEMPERATURE, UnDefType.UNDEF);
                updateState(CHANNEL_SETPOINT, UnDefType.UNDEF);
                updateState(CHANNEL_REGULATION_STATE, UnDefType.UNDEF);
                updateState(CHANNEL_HOME_SETPOINT, UnDefType.UNDEF);
                updateState(CHANNEL_AWAY_SETPOINT, UnDefType.UNDEF);
                updateState(CHANNEL_MANUAL_SETPOINT, UnDefType.UNDEF);
                updateState(CHANNEL_MANUAL, UnDefType.UNDEF);
            } else if (getThing().getStatus() != ThingStatus.ONLINE) {
                updateStatus(ThingStatus.UNKNOWN);
            }
        }
    }

    void publishHomeSelection(HomeSelection selection, WorldState snapshot) {
        synchronized (lifecycle) {
            if (running) {
                updateState(CHANNEL_HOME_SELECTED, selection.state(roomId, snapshot));
            }
        }
    }

    void publish(WorldState snapshot) {
        synchronized (lifecycle) {
            if (!running) {
                return;
            }
            WorldState.Room room = snapshot.rooms().get(roomId);
            if (room == null) {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                        "Room missing from controller response");
                updateState(CHANNEL_TEMPERATURE, UnDefType.UNDEF);
                updateState(CHANNEL_SETPOINT, UnDefType.UNDEF);
                updateState(CHANNEL_REGULATION_STATE, UnDefType.UNDEF);
                updateState(CHANNEL_HOME_SETPOINT, UnDefType.UNDEF);
                updateState(CHANNEL_AWAY_SETPOINT, UnDefType.UNDEF);
                updateState(CHANNEL_MANUAL_SETPOINT, UnDefType.UNDEF);
                updateState(CHANNEL_MANUAL, UnDefType.UNDEF);
                return;
            }
            String zone = room.zone();
            String livingZone = "Living".equals(zone) ? "true" : "None".equals(zone) ? "false" : null;
            if (!Objects.equals(getThing().getProperties().get(PROPERTY_LIVING_ZONE), livingZone)) {
                updateProperty(PROPERTY_LIVING_ZONE, livingZone);
            }
            stateDescriptionProvider.update(getThing().getUID(), room.minimum(), room.maximum(), snapshot.unit());
            updateState(CHANNEL_TEMPERATURE, temperature(room.temperature(), snapshot.unit()));
            updateState(CHANNEL_SETPOINT, temperature(room.setpoint(), snapshot.unit()));
            updateState(CHANNEL_HOME_SETPOINT, temperature(room.homeSetpoint(), snapshot.unit()));
            updateState(CHANNEL_AWAY_SETPOINT, temperature(room.awaySetpoint(), snapshot.unit()));
            updateState(CHANNEL_MANUAL_SETPOINT, temperature(room.manualSetpoint(), snapshot.unit()));
            String type = room.regulationType();
            updateState(CHANNEL_MANUAL,
                    "Manual".equals(type) ? OnOffType.ON : "Schedule".equals(type) ? OnOffType.OFF : UnDefType.UNDEF);
            String regulation = room.regulationState();
            updateState(CHANNEL_REGULATION_STATE, regulation == null ? UnDefType.UNDEF : new StringType(regulation));
            updateStatus(ThingStatus.ONLINE);
        }
    }

    private static State temperature(@Nullable BigDecimal value, @Nullable Unit<Temperature> unit) {
        return value == null || unit == null ? UnDefType.UNDEF : new QuantityType<>(value, unit);
    }
}
