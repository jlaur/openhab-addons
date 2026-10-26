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

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.nest.internal.nativeapi.dto.NativeDtos.Structure;
import org.openhab.core.library.types.OnOffType;
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
 * Exposes the observed home/away mode and submits explicit structure mode changes.
 *
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
public class NativeStructureHandler extends BaseThingHandler {
    private String structureId = "";
    private volatile boolean disposed;

    NativeStructureHandler(Thing thing) {
        super(thing);
    }

    @Override
    public void initialize() {
        disposed = false;
        Object value = getConfig().get("structureId");
        structureId = value instanceof String id ? id : "";
        refreshFromBridge();
    }

    @Override
    public void dispose() {
        disposed = true;
    }

    @Override
    public void bridgeStatusChanged(ThingStatusInfo status) {
        refreshFromBridge();
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        if (disposed || !"away".equals(channelUID.getId())) {
            return;
        }
        Bridge bridge = getBridge();
        if (bridge != null && bridge.getHandler() instanceof NativeAccountHandler account) {
            if (command == RefreshType.REFRESH) {
                account.requestRefresh();
                refreshFromBridge();
            } else if (command instanceof OnOffType mode && !account.setAway(structureId, mode == OnOffType.ON)) {
                refreshFromBridge();
            }
        }
    }

    void refreshFromBridge() {
        if (disposed) {
            return;
        }
        if (structureId.isBlank()) {
            updateStatus(ThingStatusDetail.CONFIGURATION_ERROR, "A structureId is required");
            return;
        }
        Bridge bridge = getBridge();
        if (bridge == null || bridge.getStatus() != ThingStatus.ONLINE
                || !(bridge.getHandler() instanceof NativeAccountHandler account)) {
            updateStatus(ThingStatusDetail.BRIDGE_OFFLINE);
            return;
        }
        Structure structure = account.structures().get(structureId);
        if (structure == null) {
            updateStatus(ThingStatusDetail.COMMUNICATION_ERROR, "Structure not present in the account snapshot");
            return;
        }
        Boolean away = structure.away;
        updateState("away", away == null ? UnDefType.UNDEF : OnOffType.from(away));
        updateStatus(ThingStatus.ONLINE);
    }
}
