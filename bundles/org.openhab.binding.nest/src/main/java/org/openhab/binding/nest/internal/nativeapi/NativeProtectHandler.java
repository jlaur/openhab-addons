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

import java.time.Instant;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.nest.internal.nativeapi.dto.NativeDtos.Protect;
import org.openhab.core.library.types.DateTimeType;
import org.openhab.core.library.types.OnOffType;
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

/**
 * Maps observed Protect values without inferring a normal alarm state from missing data.
 *
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
public class NativeProtectHandler extends BaseThingHandler {
    private String deviceId = "";
    private volatile boolean disposed;

    NativeProtectHandler(Thing thing) {
        super(thing);
    }

    @Override
    public void initialize() {
        disposed = false;
        Object value = getConfig().get("deviceId");
        deviceId = value instanceof String id ? id : "";
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
        if (disposed) {
            return;
        }
        if (command == RefreshType.REFRESH) {
            Bridge bridge = getBridge();
            if (bridge != null && bridge.getHandler() instanceof NativeAccountHandler account) {
                account.requestRefresh();
            }
            refreshFromBridge();
        } else if ("pathlight-mode".equals(channelUID.getId()) && command instanceof StringType value) {
            NativePathlightMode mode;
            try {
                mode = NativePathlightMode.valueOf(value.toString());
            } catch (IllegalArgumentException e) {
                return;
            }
            Bridge bridge = getBridge();
            if (bridge != null && bridge.getHandler() instanceof NativeAccountHandler account
                    && !account.setPathlight(deviceId, mode)) {
                refreshFromBridge();
            }
        }
    }

    void refreshFromBridge() {
        if (disposed) {
            return;
        }
        if (deviceId.isBlank()) {
            updateStatus(ThingStatusDetail.CONFIGURATION_ERROR, "A Protect deviceId is required");
            return;
        }
        Bridge bridge = getBridge();
        if (bridge == null || bridge.getStatus() != ThingStatus.ONLINE
                || !(bridge.getHandler() instanceof NativeAccountHandler account)) {
            updateStatus(ThingStatusDetail.BRIDGE_OFFLINE);
            return;
        }
        Protect p = account.devices().get(deviceId);
        if (p == null) {
            updateStatus(ThingStatusDetail.COMMUNICATION_ERROR, "Protect not present in the account snapshot");
            return;
        }
        updateState("smoke-status", alarm(p.smoke));
        updateState("co-status", alarm(p.carbonMonoxide));
        updateState("battery-health", battery(p.battery));
        updateState("line-power", bool(p.linePower));
        updateState("online", bool(p.online));
        updateState("replace-by", date(p.replaceBy));
        updateState("last-test-started", date(p.testStarted));
        updateState("last-test-ended", date(p.testEnded));
        NativePathlightMode mode = NativePathlightMode.observed(p);
        updateState("pathlight-mode", mode == null ? UnDefType.UNDEF : new StringType(mode.name()));
        if (Boolean.FALSE.equals(p.online)) {
            updateStatus(ThingStatusDetail.COMMUNICATION_ERROR, "Protect reports offline");
        } else {
            updateStatus(ThingStatus.ONLINE);
        }
    }

    static State alarm(@Nullable Integer value) {
        if (value == null) {
            return UnDefType.UNDEF;
        }
        return switch (value) {
            case 0 -> new StringType("NONE");
            case 1 -> new StringType("HEADS_UP_1");
            case 2 -> new StringType("HEADS_UP_2");
            case 3 -> new StringType("ALARM");
            default -> UnDefType.UNDEF;
        };
    }

    static State battery(@Nullable Integer value) {
        if (value == null) {
            return UnDefType.UNDEF;
        }
        return switch (value) {
            case 0 -> new StringType("GOOD");
            case 1 -> new StringType("SIX_MONTHS");
            case 2 -> new StringType("THREE_MONTHS");
            case 3 -> new StringType("FOUR_WEEKS");
            case 4 -> new StringType("TWO_WEEKS");
            default -> UnDefType.UNDEF;
        };
    }

    private static State bool(@Nullable Boolean value) {
        return value == null ? UnDefType.UNDEF : OnOffType.from(value);
    }

    private static State date(@Nullable Long value) {
        if (value == null || value <= 0) {
            return UnDefType.UNDEF;
        }
        try {
            return new DateTimeType(Instant.ofEpochSecond(value));
        } catch (java.time.DateTimeException e) {
            return UnDefType.UNDEF;
        }
    }
}
