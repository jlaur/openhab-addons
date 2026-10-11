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

import java.time.DateTimeException;
import java.time.Instant;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.veluxactive.internal.api.dto.HomeStatus;
import org.openhab.binding.veluxactive.internal.api.dto.Module;
import org.openhab.core.library.types.DateTimeType;
import org.openhab.core.library.types.PercentType;
import org.openhab.core.library.types.StopMoveType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.library.types.UpDownType;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.openhab.core.types.UnDefType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reports NXO windows and covers and sends commands that do not require gateway signing keys.
 *
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
public class VeluxActiveOpeningHandler extends VeluxActiveModuleHandler {
    private final Logger logger = LoggerFactory.getLogger(VeluxActiveOpeningHandler.class);
    private @Nullable String veluxType;

    public VeluxActiveOpeningHandler(Thing thing) {
        super(thing);
    }

    @Override
    public void initialize() {
        synchronized (this) {
            veluxType = null;
        }
        super.initialize();
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        if (command == RefreshType.REFRESH) {
            super.handleCommand(channelUID, command);
            return;
        }
        if (!CHANNEL_POSITION.equals(channelUID.getId())) {
            return;
        }
        Target target;
        String type;
        String gateway;
        synchronized (this) {
            target = target();
            type = veluxType;
            Target parent = target == null ? null : target.parent();
            gateway = parent == null ? null : parent.moduleId();
            if (target == null || getThing().getStatus() != ThingStatus.ONLINE) {
                return;
            }
        }
        VeluxActiveAccountHandler account = account();
        if (account == null) {
            return;
        }
        // The app's stop command applies to every opening on the gateway.
        if (command == StopMoveType.STOP) {
            if (gateway != null && !gateway.isBlank()) {
                account.stopMovements(target, gateway);
            } else {
                logger.warn("Cannot stop movements for {} until its gateway is known", getThing().getUID());
            }
            return;
        }
        int position;
        if (command instanceof PercentType percent) {
            position = percent.intValue();
        } else if (command instanceof UpDownType direction) {
            position = direction == UpDownType.UP ? 0 : 100;
        } else {
            return;
        }
        if (!knownType(type)) {
            logger.warn("Cannot control {} until its window/cover type is known", getThing().getUID());
            return;
        }
        int apiPosition = "window".equals(type) ? 100 - position : position;
        if ("window".equals(type) && apiPosition != 0) {
            logger.warn("Opening windows requires signing, which is not yet supported; ignoring command for {}",
                    getThing().getUID());
            return;
        }
        if (gateway == null || gateway.isBlank()) {
            logger.warn("Cannot control {} until its gateway is known", getThing().getUID());
            return;
        }
        account.setPosition(target, gateway, apiPosition);
    }

    @Override
    synchronized void update(Target target, HomeStatus status) {
        if (!current(target)) {
            return;
        }
        Module module = status.module(target.moduleId());
        if (module != null && !checkGateway(target, module)) {
            return;
        }
        if (status.error(target.moduleId()) != null || module != null && Boolean.FALSE.equals(module.reachable)) {
            failed(target, "Window/cover is unreachable.");
            return;
        }
        if (module == null) {
            clearState();
            updateStatus(ThingStatus.UNKNOWN, ThingStatusDetail.NONE,
                    "Window/cover is absent from the latest home status.");
            return;
        }
        var properties = editProperties();
        String type = module.veluxType;
        if (type != null) {
            veluxType = type;
            properties.put("veluxType", type);
        }
        Target parent = target.parent();
        String bridge = parent == null ? null : parent.moduleId();
        if (bridge != null) {
            properties.put("gatewayId", bridge);
        }
        Integer firmware = module.firmwareRevision;
        if (firmware != null) {
            properties.put(Thing.PROPERTY_FIRMWARE_VERSION, firmware.toString());
        }
        String manufacturer = module.manufacturer;
        if (manufacturer != null) {
            properties.put(Thing.PROPERTY_VENDOR, manufacturer);
        }
        updateProperties(properties);
        Integer position = position(module.currentPosition);
        updateState(CHANNEL_POSITION, position == null ? UnDefType.UNDEF : new PercentType(position));
        String mode = module.mode;
        updateState(CHANNEL_MODE, mode == null ? UnDefType.UNDEF : new StringType(mode));
        Long seen = module.lastSeen;
        try {
            updateState(CHANNEL_LAST_SEEN,
                    seen == null ? UnDefType.UNDEF : new DateTimeType(Instant.ofEpochSecond(seen)));
        } catch (DateTimeException e) {
            updateState(CHANNEL_LAST_SEEN, UnDefType.UNDEF);
        }
        updateStatus(ThingStatus.ONLINE);
    }

    synchronized void positionAccepted(Target target, int apiPosition) {
        if (current(target)) {
            Integer expected = position(apiPosition);
            if (expected != null) {
                // Acceptance predicts the destination; status polls reconcile the actual position.
                updateState(CHANNEL_POSITION, new PercentType(expected));
            }
        }
    }

    private @Nullable Integer position(@Nullable Integer value) {
        if (value == null || value < 0 || value > 100 || !knownType(veluxType)) {
            return null;
        }
        // VELUX uses 0 = closed for windows and 100 = closed for covers.
        return "window".equals(veluxType) ? 100 - value : value;
    }

    private static boolean knownType(@Nullable String type) {
        return type != null && switch (type) {
            case "window", "blind", "venetian_blind", "shutter", "awning_blind" -> true;
            default -> false;
        };
    }

    @Override
    protected void clearState() {
        updateState(CHANNEL_POSITION, UnDefType.UNDEF);
        updateState(CHANNEL_MODE, UnDefType.UNDEF);
        updateState(CHANNEL_LAST_SEEN, UnDefType.UNDEF);
    }
}
