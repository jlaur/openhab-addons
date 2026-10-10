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

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.veluxactive.internal.api.dto.HomeStatus;
import org.openhab.binding.veluxactive.internal.config.VeluxActiveModuleConfiguration;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.ThingStatusInfo;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;

/**
 * Shares account connectivity and lifecycle handling for physical modules.
 *
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
public abstract class VeluxActiveModuleHandler extends BaseThingHandler {
    private long generation;
    private boolean active;
    private @Nullable VeluxActiveModuleConfiguration configuration;

    protected VeluxActiveModuleHandler(Thing thing) {
        super(thing);
    }

    @Override
    public void initialize() {
        synchronized (this) {
            active = true;
            generation++;
            configuration = getConfigAs(VeluxActiveModuleConfiguration.class);
        }
        refreshFromBridge();
    }

    private void refreshFromBridge() {
        Target target = target();
        if (target == null) {
            synchronized (this) {
                if (active) {
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                            "Home ID and module ID are required.");
                }
            }
            return;
        }
        Bridge bridge = getBridge();
        VeluxActiveAccountHandler account = account();
        synchronized (this) {
            if (!current(target)) {
                return;
            }
            if (bridge == null || account == null) {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_UNINITIALIZED);
                return;
            }
            if (bridge.getStatus() != ThingStatus.ONLINE) {
                clearState();
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_OFFLINE);
                return;
            }
            updateStatus(ThingStatus.UNKNOWN);
        }
        // Never acquire the account monitor while holding the child monitor.
        account.requestPoll();
    }

    @Override
    public void bridgeStatusChanged(ThingStatusInfo status) {
        synchronized (this) {
            if (!active) {
                return;
            }
            generation++;
        }
        refreshFromBridge();
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        if (command == RefreshType.REFRESH && target() != null) {
            VeluxActiveAccountHandler account = account();
            if (account != null) {
                account.requestPoll();
            }
        }
    }

    protected @Nullable VeluxActiveAccountHandler account() {
        Bridge bridge = getBridge();
        return bridge != null && bridge.getHandler() instanceof VeluxActiveAccountHandler account ? account : null;
    }

    synchronized @Nullable Target target() {
        VeluxActiveModuleConfiguration configuration = this.configuration;
        if (!active || configuration == null || configuration.homeId.isBlank() || configuration.moduleId.isBlank()) {
            return null;
        }
        return new Target(this, configuration.homeId, configuration.moduleId, generation);
    }

    synchronized boolean current(Target target) {
        return active && generation == target.generation();
    }

    abstract void update(Target target, HomeStatus status);

    synchronized void failed(Target target, @Nullable String message) {
        if (current(target)) {
            clearState();
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, message);
        }
    }

    protected abstract void clearState();

    @Override
    public synchronized void dispose() {
        active = false;
        generation++;
    }

    record Target(VeluxActiveModuleHandler handler, String homeId, String moduleId, long generation) {
    }
}
