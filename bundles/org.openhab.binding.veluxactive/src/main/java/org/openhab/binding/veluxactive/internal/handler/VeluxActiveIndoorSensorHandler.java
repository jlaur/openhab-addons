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

import static org.openhab.binding.veluxactive.internal.VeluxActiveBindingConstants.*;

import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.veluxactive.internal.VeluxActiveApi;
import org.openhab.binding.veluxactive.internal.api.dto.Home;
import org.openhab.binding.veluxactive.internal.api.dto.HomeStatus;
import org.openhab.binding.veluxactive.internal.api.dto.Module;
import org.openhab.binding.veluxactive.internal.api.dto.Room;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.unit.SIUnits;
import org.openhab.core.library.unit.Units;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.types.UnDefType;

/**
 * Combines physical NXS diagnostics with measurements from its assigned room.
 *
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
public class VeluxActiveIndoorSensorHandler extends VeluxActiveBatteryModuleHandler {
    private @Nullable String roomId;

    public VeluxActiveIndoorSensorHandler(Thing thing) {
        super(thing);
    }

    @Override
    public void initialize() {
        synchronized (this) {
            roomId = null;
        }
        super.initialize();
    }

    synchronized void updateTopology(Target target, List<VeluxActiveApi.ModuleInfo> modules) {
        if (!current(target)) {
            return;
        }
        var info = modules
                .stream().filter(module -> target.homeId().equals(module.homeId())
                        && target.moduleId().equals(module.moduleId()) && "NXS".equals(module.type()))
                .findFirst().orElse(null);
        roomId = info == null ? null : info.roomId();
        String roomName = info == null ? null : info.roomName();
        var properties = editProperties();
        String room = roomId;
        if (room != null && !room.isBlank()) {
            properties.put("roomId", room);
        } else {
            properties.remove("roomId");
        }
        if (roomName != null && !roomName.isBlank()) {
            properties.put("roomName", roomName);
        } else {
            properties.remove("roomName");
        }
        updateProperties(properties);
    }

    @Override
    synchronized void update(Target target, HomeStatus status) {
        if (!current(target)) {
            return;
        }
        Module module = status.module(target.moduleId());
        if (status.error(target.moduleId()) != null || module != null && Boolean.FALSE.equals(module.reachable)) {
            failed(target, "Indoor climate sensor is unreachable.");
            return;
        }
        if (module == null) {
            clearState();
            updateStatus(ThingStatus.UNKNOWN, ThingStatusDetail.NONE, "Sensor is absent from the latest home status.");
            return;
        }
        updateDiagnostics(module);
        Room room = room(status.home);
        if (room == null) {
            clearClimate();
        } else {
            Integer temperature = room.temperature;
            Integer humidity = room.humidity;
            Integer co2 = room.co2;
            Integer light = room.lux;
            updateState(CHANNEL_TEMPERATURE,
                    temperature == null ? UnDefType.UNDEF : new QuantityType<>(temperature / 10.0, SIUnits.CELSIUS));
            updateState(CHANNEL_HUMIDITY,
                    humidity != null && humidity >= 0 && humidity <= 100 ? new QuantityType<>(humidity, Units.PERCENT)
                            : UnDefType.UNDEF);
            updateState(CHANNEL_CO2,
                    co2 != null && co2 >= 0 ? new QuantityType<>(co2, Units.PARTS_PER_MILLION) : UnDefType.UNDEF);
            updateState(CHANNEL_ILLUMINANCE,
                    light != null && light >= 0 ? new QuantityType<>(light, Units.LUX) : UnDefType.UNDEF);
        }
        updateStatus(ThingStatus.ONLINE);
    }

    private @Nullable Room room(@Nullable Home home) {
        String id = roomId;
        List<@Nullable Room> rooms = home == null ? null : home.rooms;
        if (id != null && rooms != null) {
            for (Room room : rooms) {
                if (room != null && id.equals(room.id)) {
                    return room;
                }
            }
        }
        return null;
    }

    private void clearClimate() {
        updateState(CHANNEL_TEMPERATURE, UnDefType.UNDEF);
        updateState(CHANNEL_HUMIDITY, UnDefType.UNDEF);
        updateState(CHANNEL_CO2, UnDefType.UNDEF);
        updateState(CHANNEL_ILLUMINANCE, UnDefType.UNDEF);
    }

    @Override
    protected void clearState() {
        clearClimate();
        super.clearState();
    }
}
