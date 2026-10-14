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

import javax.measure.Unit;
import javax.measure.quantity.Temperature;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.types.StringType;
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

/**
 * A room's temperature, current setpoint, and regulation state.
 *
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
public class RoomHandler extends BaseThingHandler {
    private final Object lifecycle = new Object();
    private boolean running;
    private int roomId;

    RoomHandler(Thing thing) {
        super(thing);
    }

    @Override
    public void initialize() {
        Object configured = getConfig().get(ROOM_ID);
        try {
            if (!(configured instanceof BigDecimal number)) {
                throw new IllegalArgumentException("Missing room ID");
            }
            int id = number.intValueExact();
            if (id < 0) {
                throw new IllegalArgumentException("Negative room ID");
            }
            synchronized (lifecycle) {
                roomId = id;
                running = true;
            }
        } catch (IllegalArgumentException | ArithmeticException e) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "A non-negative room ID is required");
            return;
        }
        var bridge = getBridge();
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
            return;
        }
        int id;
        synchronized (lifecycle) {
            if (!running) {
                return;
            }
            id = roomId;
        }
        if (command == RefreshType.REFRESH) {
            controller.refresh();
        } else if (SETPOINT.equals(channel.getId())) {
            controller.setpoint(id, command);
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
                updateState(TEMPERATURE, UnDefType.UNDEF);
                updateState(SETPOINT, UnDefType.UNDEF);
                updateState(REGULATION_STATE, UnDefType.UNDEF);
            } else if (getThing().getStatus() != ThingStatus.ONLINE) {
                updateStatus(ThingStatus.UNKNOWN);
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
                updateState(TEMPERATURE, UnDefType.UNDEF);
                updateState(SETPOINT, UnDefType.UNDEF);
                updateState(REGULATION_STATE, UnDefType.UNDEF);
                return;
            }
            updateState(TEMPERATURE, temperature(room.temperature(), snapshot.unit()));
            updateState(SETPOINT, temperature(room.setpoint(), snapshot.unit()));
            String regulation = room.regulationState();
            updateState(REGULATION_STATE, regulation == null ? UnDefType.UNDEF : new StringType(regulation));
            updateStatus(ThingStatus.ONLINE);
        }
    }

    private static State temperature(@Nullable BigDecimal value, @Nullable Unit<Temperature> unit) {
        return value == null || unit == null ? UnDefType.UNDEF : new QuantityType<>(value, unit);
    }
}
