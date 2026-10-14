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

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.common.ThreadPoolManager;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.BaseBridgeHandler;
import org.openhab.core.thing.binding.ThingHandler;
import org.openhab.core.thing.binding.ThingHandlerService;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Serializes each controller's cloud operations and discards results from obsolete lifecycles.
 *
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
public class ControllerHandler extends BaseBridgeHandler {
    private final Logger logger = LoggerFactory.getLogger(ControllerHandler.class);
    private final ExecutorService worker = ThreadPoolManager.getPool("binding-danfosslink");
    private final PairingStore store;
    private final Object lifecycle = new Object();
    private final Map<Integer, Command> commands = new HashMap<>();
    private boolean running;
    private @Nullable Operation active;
    private @Nullable ScheduledFuture<?> polling;
    private @Nullable WorldState world;
    private @Nullable RoomDiscoveryService discovery;

    ControllerHandler(Bridge bridge, PairingStore store) {
        super(bridge);
        this.store = store;
    }

    @Override
    public void initialize() {
        dispose();
        int interval = getConfigAs(DanfossLinkConfiguration.class).refreshInterval;
        if (interval < 10) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "Refresh interval must be at least 10 seconds");
            return;
        }
        synchronized (lifecycle) {
            running = true;
            updateStatus(ThingStatus.UNKNOWN);
            polling = scheduler.scheduleWithFixedDelay(this::refresh, interval, interval, TimeUnit.SECONDS);
        }
        refresh();
    }

    @Override
    public void dispose() {
        Operation closing;
        synchronized (lifecycle) {
            running = false;
            ScheduledFuture<?> task = polling;
            polling = null;
            if (task != null) {
                task.cancel(false);
            }
            closing = active;
            active = null;
            commands.clear();
            world = null;
        }
        if (closing != null) {
            closing.close();
        }
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        if (command == RefreshType.REFRESH) {
            refresh();
        }
    }

    @Override
    public Collection<Class<? extends ThingHandlerService>> getServices() {
        return Set.of(PairingActions.class, RoomDiscoveryService.class);
    }

    @Override
    public void childHandlerInitialized(ThingHandler child, Thing childThing) {
        if (child instanceof RoomHandler room) {
            synchronized (lifecycle) {
                WorldState snapshot = world;
                if (running && snapshot != null && getThing().getStatus() == ThingStatus.ONLINE) {
                    room.publish(snapshot);
                }
            }
        }
    }

    void discovery(@Nullable RoomDiscoveryService service) {
        synchronized (lifecycle) {
            discovery = service;
            WorldState snapshot = world;
            if (running && service != null && snapshot != null) {
                service.publish(snapshot);
            }
        }
    }

    String pair(String pin) {
        if (!pin.matches("[0-9]{9}")) {
            return "Pairing code must contain exactly nine digits.";
        }
        synchronized (lifecycle) {
            if (!running) {
                return "Controller is not initialized.";
            }
            if (active != null) {
                return "Controller is busy; retry when the current operation finishes.";
            }
            if (getThing().getStatus() == ThingStatus.ONLINE) {
                return "Controller is already paired.";
            }
            Operation operation = new Operation();
            active = operation;
            updateStatus(ThingStatus.UNKNOWN, ThingStatusDetail.NONE, "Pairing in progress");
            worker.execute(() -> run(operation, pin, Map.of()));
        }
        return "Pairing started. Check the controller Thing status for the result.";
    }

    void refresh() {
        synchronized (lifecycle) {
            if (!running || active != null) {
                return;
            }
            Operation operation = new Operation();
            active = operation;
            Map<Integer, Command> pending = Map.copyOf(commands);
            commands.clear();
            worker.execute(() -> run(operation, null, pending));
        }
    }

    void setpoint(int roomId, Command command) {
        synchronized (lifecycle) {
            WorldState snapshot = world;
            if (!running || snapshot == null || !snapshot.rooms().containsKey(roomId)) {
                return;
            }
            try {
                snapshot.command(roomId, command);
            } catch (IllegalArgumentException e) {
                logger.debug("Ignoring invalid setpoint for room {}: {}", roomId, e.getMessage());
                return;
            }
            // One pending value per discovered room bounds the queue and coalesces rapid UI changes.
            commands.put(roomId, command);
        }
        refresh();
    }

    CloudClient connect(Identity identity, Operation operation) throws Exception {
        return new CloudClient(identity, operation);
    }

    private void run(Operation operation, @Nullable String pin, Map<Integer, Command> pending) {
        boolean paired = false;
        try (operation) {
            operation.check();
            PairingStore.Credentials credentials = store.load(getThing().getUID().toString());
            var controller = credentials.controller;
            if (pin == null && controller == null) {
                synchronized (lifecycle) {
                    if (current(operation)) {
                        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_PENDING,
                                "Use the pair action with the nine-digit code shown on the controller");
                    }
                }
                return;
            }
            try (var client = connect(credentials.identity, operation)) {
                if (pin != null) {
                    controller = client.pair(pin);
                    synchronized (lifecycle) {
                        if (!current(operation)) {
                            return;
                        }
                        store.save(getThing().getUID().toString(),
                                new PairingStore.Credentials(credentials.identity, controller));
                    }
                    paired = true;
                } else if (controller != null) {
                    try (var session = client.open(controller)) {
                        WorldState snapshot = session.world();
                        for (var entry : pending.entrySet()) {
                            operation.check();
                            try {
                                session.setpoint(entry.getKey(), snapshot.command(entry.getKey(), entry.getValue()));
                            } catch (IllegalArgumentException e) {
                                logger.debug("Ignoring setpoint after updated room limits: {}", e.getMessage());
                            }
                        }
                        if (!pending.isEmpty()) {
                            snapshot = session.world();
                        }
                        publish(operation, snapshot);
                    }
                }
            }
        } catch (Exception e) {
            synchronized (lifecycle) {
                if (current(operation)) {
                    logger.debug("Danfoss Link operation failed ({})", e.getClass().getSimpleName());
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                            pin == null ? "Cloud communication failed; will retry"
                                    : "Pairing failed; obtain a fresh code and retry the pair action");
                }
            }
        } finally {
            boolean again;
            synchronized (lifecycle) {
                again = current(operation) && (paired || !commands.isEmpty());
                if (operation.equals(active)) {
                    active = null;
                }
            }
            if (again) {
                refresh();
            }
        }
    }

    private boolean current(Operation operation) {
        return running && operation.equals(active);
    }

    private void publish(Operation operation, WorldState snapshot) {
        // Status and state publication share the lifecycle gate with disposal; no network I/O runs under this gate.
        synchronized (lifecycle) {
            if (!current(operation)) {
                return;
            }
            world = snapshot;
            Map<String, String> properties = new HashMap<>(getThing().getProperties());
            String serial = snapshot.serialNumber();
            String firmware = snapshot.firmware();
            if (serial != null) {
                properties.put(Thing.PROPERTY_SERIAL_NUMBER, serial);
            }
            if (firmware != null) {
                properties.put(Thing.PROPERTY_FIRMWARE_VERSION, firmware);
            }
            updateProperties(properties);
            updateStatus(ThingStatus.ONLINE);
            for (Thing child : getThing().getThings()) {
                if (child.getHandler() instanceof RoomHandler room) {
                    room.publish(snapshot);
                }
            }
            RoomDiscoveryService service = discovery;
            if (service != null) {
                service.publish(snapshot);
            }
        }
    }
}
