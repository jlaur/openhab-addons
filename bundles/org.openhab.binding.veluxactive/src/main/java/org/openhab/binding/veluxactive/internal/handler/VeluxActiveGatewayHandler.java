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
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.veluxactive.internal.api.ModuleInfo;
import org.openhab.binding.veluxactive.internal.api.dto.HomeStatus;
import org.openhab.binding.veluxactive.internal.api.dto.Module;
import org.openhab.binding.veluxactive.internal.api.dto.ModuleError;
import org.openhab.binding.veluxactive.internal.discovery.VeluxActiveGatewayDiscoveryService;
import org.openhab.core.library.types.DateTimeType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.BridgeHandler;
import org.openhab.core.thing.binding.ThingHandler;
import org.openhab.core.thing.binding.ThingHandlerService;
import org.openhab.core.thing.binding.builder.BridgeBuilder;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.openhab.core.types.UnDefType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Publishes gateway state and requests unsigned departure locking.
 *
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
public class VeluxActiveGatewayHandler extends VeluxActiveModuleHandler implements BridgeHandler {
    private final Logger logger = LoggerFactory.getLogger(VeluxActiveGatewayHandler.class);

    public VeluxActiveGatewayHandler(Bridge thing) {
        super(thing);
    }

    @Override
    public Bridge getThing() {
        return (Bridge) super.getThing();
    }

    @Override
    protected BridgeBuilder editThing() {
        return BridgeBuilder.create(thing.getThingTypeUID(), thing.getUID()).withBridge(thing.getBridgeUID())
                .withChannels(thing.getChannels()).withConfiguration(thing.getConfiguration())
                .withLabel(thing.getLabel()).withLocation(thing.getLocation()).withProperties(thing.getProperties())
                .withSemanticEquipmentTag(thing.getSemanticEquipmentTag());
    }

    @Override
    public Collection<Class<? extends ThingHandlerService>> getServices() {
        return Set.of(VeluxActiveGatewayDiscoveryService.class);
    }

    public void discover(Consumer<List<ModuleInfo>> consumer) {
        ModuleRequestContext target = captureRequestContext();
        VeluxActiveAccountHandler account = account();
        if (target != null && account != null) {
            account.discover(modules -> {
                if (isRequestContextCurrent(target)) {
                    consumer.accept(modules.stream().filter(module -> target.homeId().equals(module.homeId())
                            && target.moduleId().equals(module.bridgeId())).toList());
                }
            });
        }
    }

    @Override
    public void childHandlerInitialized(ThingHandler childHandler, Thing childThing) {
        VeluxActiveAccountHandler account = account();
        if (account != null) {
            account.requestPoll();
        }
    }

    @Override
    public void childHandlerDisposed(ThingHandler childHandler, Thing childThing) {
        VeluxActiveAccountHandler account = account();
        if (account != null) {
            account.requestPoll();
        }
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        ModuleRequestContext target = captureRequestContext();
        if (target == null) {
            return;
        }
        VeluxActiveAccountHandler account = account();
        if (account == null) {
            return;
        }
        if (command == RefreshType.REFRESH) {
            account.requestPoll();
        } else if (CHANNEL_LOCKED.equals(channelUID.getId())) {
            if (command == OnOffType.ON) {
                account.lock(target);
            } else if (command == OnOffType.OFF) {
                logger.warn("Unlocking departure mode is not supported; ignoring OFF for {}", getThing().getUID());
            }
        }
    }

    synchronized void homeResolved(boolean homeChanged) {
        ModuleRequestContext target = captureRequestContext();
        if (target == null || !isRequestContextCurrent(target)) {
            return;
        }
        if (target.homeId().isBlank()) {
            clearState();
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                    "@text/offline.comm-error.gateway.home-unresolved");
        } else if (homeChanged) {
            clearState();
        }
    }

    synchronized void update(ModuleRequestContext target, HomeStatus status) {
        if (!isRequestContextCurrent(target)) {
            return;
        }
        ModuleError error = status.error(target.moduleId());
        Module module = status.module(target.moduleId());
        if (error != null || module != null && Boolean.FALSE.equals(module.reachable)) {
            clearState();
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                    error != null && !Integer.valueOf(6).equals(error.code)
                            ? "@text/offline.comm-error.gateway.status-error"
                            : "@text/offline.comm-error.gateway.unreachable");
        } else if (module == null) {
            missingStatus("@text/status.gateway.missing");
        } else {
            var properties = editProperties();
            Integer firmware = module.firmwareRevisionNetatmo;
            Integer hardware = module.hardwareVersion;
            if (firmware != null) {
                properties.put(Thing.PROPERTY_FIRMWARE_VERSION, firmware.toString());
            }
            if (hardware != null) {
                properties.put(Thing.PROPERTY_HARDWARE_VERSION, hardware.toString());
            }
            updateProperties(properties);
            Boolean locked = module.locked;
            Boolean locking = module.locking;
            updateState(CHANNEL_LOCKED, locked == null ? UnDefType.UNDEF : OnOffType.from(locked));
            updateState(CHANNEL_LOCKING, locking == null ? UnDefType.UNDEF : OnOffType.from(locking));
            Long lastSeen = module.lastSeen;
            try {
                updateState(CHANNEL_LAST_SEEN,
                        lastSeen == null ? UnDefType.UNDEF : new DateTimeType(Instant.ofEpochSecond(lastSeen)));
            } catch (DateTimeException e) {
                updateState(CHANNEL_LAST_SEEN, UnDefType.UNDEF);
            }
            updateStatus(ThingStatus.ONLINE);
        }
    }

    synchronized void lockAccepted(ModuleRequestContext target) {
        if (isRequestContextCurrent(target)) {
            // The API acknowledged the command; the immediate status poll reconciles actual gateway state.
            updateState(CHANNEL_LOCKED, OnOffType.ON);
        }
    }

    @Override
    protected void clearState() {
        updateState(CHANNEL_LOCKED, UnDefType.UNDEF);
        updateState(CHANNEL_LOCKING, UnDefType.UNDEF);
        updateState(CHANNEL_LAST_SEEN, UnDefType.UNDEF);
    }
}
