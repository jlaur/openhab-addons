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

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.danfosslink.internal.CloudClient;
import org.openhab.binding.danfosslink.internal.PairingStore;
import org.openhab.binding.danfosslink.internal.WorldState;
import org.openhab.binding.danfosslink.internal.action.PairingActions;
import org.openhab.binding.danfosslink.internal.config.ControllerConfiguration;
import org.openhab.binding.danfosslink.internal.discovery.RoomDiscoveryService;
import org.openhab.binding.danfosslink.internal.protocol.ControllerTime;
import org.openhab.binding.danfosslink.internal.protocol.Identity;
import org.openhab.binding.danfosslink.internal.protocol.Operation;
import org.openhab.core.common.ThreadPoolManager;
import org.openhab.core.i18n.TimeZoneProvider;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.StringType;
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
import org.openhab.core.types.UnDefType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Serializes each controller's cloud operations and discards results from obsolete lifecycles.
 *
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
public class ControllerHandler extends BaseBridgeHandler {

    private static final Set<String> VACATION_CHANNELS = Set.of(CHANNEL_VACATION, CHANNEL_VACATION_STATE,
            CHANNEL_VACATION_START, CHANNEL_VACATION_END);

    private static final Set<String> PAUSE_CHANNELS = Set.of(CHANNEL_PAUSE, CHANNEL_PAUSE_UNTIL);

    private final Logger logger = LoggerFactory.getLogger(ControllerHandler.class);
    private final ExecutorService worker = ThreadPoolManager.getPool("binding-danfosslink");
    private final PairingStore store;
    private final Clock clock;
    private final TimeZoneProvider timeZoneProvider;
    private final Object lifecycle = new Object();

    private record CommandTarget(@Nullable Integer roomId, String channel) {
        WorldState.Patch patch(WorldState snapshot, Command command) {
            Integer id = roomId;
            return id == null ? WorldState.livingZonePatch(channel, command) : snapshot.patch(id, channel, command);
        }
    }

    private final Map<CommandTarget, Command> commands = new LinkedHashMap<>();

    private final Map<String, VacationState.Request> vacationCommands = new LinkedHashMap<>();
    private PauseState.@Nullable Request pauseCommand;
    private HomeSelection homeSelection = new HomeSelection();
    private HomeSelection.@Nullable Request homeCommand;
    private VacationState vacation = new VacationState();

    private boolean running;
    private boolean clockWarningLogged;
    private @Nullable Operation active;
    private @Nullable ScheduledFuture<?> polling;
    private @Nullable WorldState world;
    private @Nullable RoomDiscoveryService discovery;

    public ControllerHandler(Bridge bridge, PairingStore store, TimeZoneProvider timeZoneProvider) {
        this(bridge, store, Clock.systemUTC(), timeZoneProvider);
    }

    ControllerHandler(Bridge bridge, PairingStore store) {
        this(bridge, store, Clock.systemUTC());
    }

    ControllerHandler(Bridge bridge, PairingStore store, Clock clock) {
        this(bridge, store, clock, clock::getZone);
    }

    ControllerHandler(Bridge bridge, PairingStore store, Clock clock, TimeZoneProvider timeZoneProvider) {
        super(bridge);
        this.timeZoneProvider = timeZoneProvider;
        this.store = store;
        this.clock = clock;
    }

    @Override
    public void initialize() {
        int interval = getConfigAs(ControllerConfiguration.class).refreshInterval;
        if (interval < ControllerConfiguration.MINIMUM_REFRESH_INTERVAL) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "Refresh interval must be at least " + ControllerConfiguration.MINIMUM_REFRESH_INTERVAL
                            + " seconds");
            return;
        }
        synchronized (lifecycle) {
            clockWarningLogged = false;
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
            vacationCommands.clear();
            vacation = new VacationState();
            pauseCommand = null;
            homeCommand = null;
            homeSelection = new HomeSelection();
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
        } else if (CHANNEL_HOME.equals(channelUID.getId())) {
            synchronized (lifecycle) {
                WorldState snapshot = world;
                if (!running || snapshot == null) {
                    return;
                }
                if (command instanceof OnOffType onOff) {
                    try {
                        var request = homeSelection.request(onOff);
                        request.body(snapshot);
                        homeCommand = request;
                    } catch (IllegalArgumentException e) {
                        logger.debug("Controller {} rejected Home command: {}", getThing().getUID(), e.getMessage());
                    }
                } else {
                    logger.debug("Controller {} rejected Home command type {}", getThing().getUID(),
                            command.getClass().getSimpleName());
                }
                updateState(CHANNEL_HOME, HomeSelection.reported(snapshot.home()));
                if (homeCommand == null) {
                    return;
                }
            }
            refresh();
        } else if (PAUSE_CHANNELS.contains(channelUID.getId())) {
            synchronized (lifecycle) {
                WorldState snapshot = world;
                if (!running || snapshot == null || snapshot.pause() == null) {
                    return;
                }
                try {
                    // Both channels address the same override; the latest pending command replaces earlier intent.
                    pauseCommand = PauseState.command(channelUID.getId(), command, clock.instant());
                } catch (IllegalArgumentException e) {
                    logger.debug("Controller {} rejected pause command: {}", getThing().getUID(), e.getMessage());
                }
                publishPause(snapshot.pause());
                if (pauseCommand == null) {
                    return;
                }
            }
            refresh();
        } else if (VACATION_CHANNELS.contains(channelUID.getId())) {
            synchronized (lifecycle) {
                if (!running) {
                    return;
                }
                try {
                    var request = vacation.command(channelUID.getId(), command, clock.instant());
                    if (request != null) {
                        if (command == org.openhab.core.library.types.OnOffType.OFF) {
                            vacationCommands.clear();
                        }
                        vacationCommands.remove(channelUID.getId());
                        vacationCommands.put(channelUID.getId(), request);
                    }
                } catch (IllegalArgumentException e) {
                    logger.debug("Controller {} rejected vacation command: {}", getThing().getUID(), e.getMessage());
                }
                publishVacation();
                if (vacationCommands.isEmpty()) {
                    return;
                }
            }
            refresh();
        }
    }

    private void publishPause(WorldState.@Nullable Mode pause) {
        for (String channel : PAUSE_CHANNELS) {
            updateState(channel, PauseState.state(pause, channel));
        }
    }

    private void publishVacation() {
        for (String channel : VACATION_CHANNELS) {
            updateState(channel, vacation.state(channel));
        }
    }

    @Override
    public Collection<Class<? extends ThingHandlerService>> getServices() {
        return Set.of(PairingActions.class, RoomDiscoveryService.class);
    }

    @Override
    public void childHandlerInitialized(ThingHandler child, Thing childThing) {
        synchronized (lifecycle) {
            WorldState snapshot = world;
            if (running && snapshot != null && getThing().getStatus() == ThingStatus.ONLINE) {
                publishChild(child, snapshot);
            }
        }
    }

    private void publishChild(@Nullable ThingHandler child, WorldState snapshot) {
        if (child instanceof RoomHandler room) {
            room.publish(snapshot);
            room.publishHomeSelection(homeSelection, snapshot);
        } else if (child instanceof LivingZoneHandler zone) {
            zone.publish(snapshot);
            zone.publishHomeSelection(homeSelection, snapshot);
        }
    }

    void selectHome(@Nullable Integer roomId, Command command) {
        synchronized (lifecycle) {
            WorldState snapshot = world;
            if (!running || snapshot == null) {
                return;
            }
            if (command instanceof OnOffType onOff) {
                try {
                    homeSelection.select(roomId, onOff, snapshot);
                } catch (IllegalArgumentException e) {
                    logger.debug("Controller {} rejected Home selection: {}", getThing().getUID(), e.getMessage());
                }
            } else {
                logger.debug("Controller {} rejected Home selection command type {}", getThing().getUID(),
                        command.getClass().getSimpleName());
            }
            for (Thing child : getThing().getThings()) {
                if (child.getHandler() instanceof RoomHandler room) {
                    room.publishHomeSelection(homeSelection, snapshot);
                } else if (child.getHandler() instanceof LivingZoneHandler zone) {
                    zone.publishHomeSelection(homeSelection, snapshot);
                }
            }
        }
    }

    public void discovery(@Nullable RoomDiscoveryService service) {
        synchronized (lifecycle) {
            discovery = service;
            WorldState snapshot = world;
            if (running && service != null && snapshot != null) {
                service.publish(snapshot);
            }
        }
    }

    public String pair(String pin) {
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
            worker.execute(() -> run(operation, pin, Map.of(), Map.of(), null, null));
        }
        return "Pairing started. Check the controller Thing status for the result.";
    }

    public void refresh() {
        synchronized (lifecycle) {
            if (!running || active != null) {
                return;
            }
            Operation operation = new Operation();
            active = operation;
            Map<CommandTarget, Command> pending = new LinkedHashMap<>(commands);
            commands.clear();
            Map<String, VacationState.Request> pendingVacation = new LinkedHashMap<>(vacationCommands);
            vacationCommands.clear();
            PauseState.Request pendingPause = pauseCommand;
            pauseCommand = null;
            HomeSelection.Request pendingHome = homeCommand;
            homeCommand = null;
            worker.execute(() -> run(operation, null, pending, pendingVacation, pendingPause, pendingHome));
        }
    }

    void roomCommand(int roomId, String channel, Command command) {
        queueCommand(new CommandTarget(roomId, channel), command);
    }

    void livingZoneCommand(String channel, Command command) {
        queueCommand(new CommandTarget(null, channel), command);
    }

    private void queueCommand(CommandTarget target, Command command) {
        synchronized (lifecycle) {
            WorldState snapshot = world;
            if (!running || snapshot == null) {
                logger.debug("Ignoring command for {}: running={}, controller snapshot available={}", target, running,
                        snapshot != null);
                return;
            }
            try {
                target.patch(snapshot, command);
            } catch (IllegalArgumentException e) {
                logger.debug("Ignoring invalid command for {}: {}", target, e.getMessage());
                return;
            }
            // Coalesce each channel independently, keeping the order of the latest commands.
            var key = target;
            commands.remove(key);
            commands.put(key, command);
        }
        refresh();
    }

    CloudClient connect(Identity identity, Operation operation) throws Exception {
        return new CloudClient(identity, operation);
    }

    private void run(Operation operation, @Nullable String pin, Map<CommandTarget, Command> pending,
            Map<String, VacationState.Request> pendingVacation, PauseState.@Nullable Request pendingPause,
            HomeSelection.@Nullable Request pendingHome) {
        boolean paired = false;
        String stage = "loading pairing";
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
            stage = "connecting to cloud";
            try (var client = connect(credentials.identity, operation)) {
                if (pin != null) {
                    stage = "pairing";
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
                    stage = "opening controller session";
                    try (var session = client.open(controller)) {
                        stage = "reading controller state";
                        Snapshot snapshot = readWorld(session);
                        for (var request : pendingVacation.values()) {
                            operation.check();
                            try {
                                var mode = snapshot.state().vacation();
                                var body = request.body(mode, clock.instant(), snapshot.time());
                                stage = "writing /api/modes/vacation";
                                session.vacation(body);
                                stage = "reading back vacation";
                                snapshot = readWorld(session);
                            } catch (IllegalArgumentException e) {
                                logger.debug("Controller {} rejected vacation command: {}", getThing().getUID(),
                                        e.getMessage());
                            } catch (CloudClient.ControllerResponseException e) {
                                logger.debug("Controller {} rejected vacation request with HTTP {}",
                                        getThing().getUID(), e.status());
                                stage = "reading state after rejected vacation request";
                                snapshot = readWorld(session);
                            }
                        }
                        if (pendingPause != null) {
                            operation.check();
                            try {
                                var mode = snapshot.state().pause();
                                var body = pendingPause.body(mode, clock.instant(), snapshot.time());
                                stage = "writing /api/modes/pause";
                                session.pause(body);
                                stage = "reading back pause";
                                snapshot = readWorld(session);
                            } catch (IllegalArgumentException e) {
                                logger.debug("Controller {} rejected pause command: {}", getThing().getUID(),
                                        e.getMessage());
                            } catch (CloudClient.ControllerResponseException e) {
                                logger.debug("Controller {} rejected pause request with HTTP {}", getThing().getUID(),
                                        e.status());
                                stage = "reading state after rejected pause request";
                                snapshot = readWorld(session);
                            }
                        }
                        if (pendingHome != null) {
                            operation.check();
                            try {
                                var body = pendingHome.body(snapshot.state());
                                stage = "writing /api/modes/athome";
                                session.home(body);
                                stage = "reading back Home";
                                snapshot = readWorld(session);
                            } catch (IllegalArgumentException e) {
                                logger.debug("Controller {} rejected Home command after refresh: {}",
                                        getThing().getUID(), e.getMessage());
                            } catch (CloudClient.ControllerResponseException e) {
                                logger.debug("Controller {} rejected Home request with HTTP {}", getThing().getUID(),
                                        e.status());
                                stage = "reading state after rejected Home request";
                                snapshot = readWorld(session);
                            }
                        }
                        boolean refreshRequired = false;
                        for (var entry : pending.entrySet()) {
                            operation.check();
                            try {
                                var key = entry.getKey();
                                var patch = key.patch(snapshot.state(), entry.getValue());
                                stage = "writing " + patch.uri();
                                logger.trace("Controller {} applying {} for {}", getThing().getUID(), entry.getValue(),
                                        key);
                                session.apply(patch);
                                refreshRequired = true;
                            } catch (IllegalArgumentException e) {
                                logger.debug("Ignoring command after updated room configuration: {}", e.getMessage());
                            }
                        }
                        if (refreshRequired) {
                            stage = "reading back controller state";
                            snapshot = readWorld(session);
                        }
                        publish(operation, snapshot.state());
                    }
                }
            }
        } catch (Exception e) {
            synchronized (lifecycle) {
                if (current(operation)) {
                    for (String channel : VACATION_CHANNELS) {
                        updateState(channel, UnDefType.UNDEF);
                    }
                    publishPause(null);
                    updateState(CHANNEL_HOME, UnDefType.UNDEF);
                    updateState(CHANNEL_CURRENT_STATE, UnDefType.UNDEF);
                    logger.debug("Controller {} operation failed during {} ({})", getThing().getUID(), stage,
                            e.getClass().getSimpleName());
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                            pin == null
                                    ? e instanceof CloudClient.ControllerResponseException rejection
                                            ? "Controller returned HTTP " + rejection.status() + " while " + stage
                                            : "Cloud communication failed while " + stage + "; will retry"
                                    : "Pairing failed; obtain a fresh code and retry the pair action");
                }
            }
        } finally {
            boolean again;
            synchronized (lifecycle) {
                again = current(operation) && (paired || !commands.isEmpty() || !vacationCommands.isEmpty()
                        || pauseCommand != null || homeCommand != null);
                if (operation.equals(active)) {
                    active = null;
                }
            }
            if (again) {
                refresh();
            }
        }
    }

    private record Snapshot(WorldState state, ControllerTime time) {
    }

    private Snapshot readWorld(CloudClient.Session session) throws IOException {
        WorldState raw = session.world(timeZoneProvider.getTimeZone());
        // Keep each conversion with its own response; never reuse a skew after reconnect or clock correction.
        ControllerTime time = ControllerTime.observed(raw.systemTime(), clock.instant());
        var state = new WorldState(raw.rooms(), raw.unit(), raw.brandingType(), raw.serialNumber(), raw.firmware(),
                raw.systemTime(), time.toOpenHab(raw.vacation()), time.toOpenHab(raw.pause()),
                time.toOpenHab(raw.home()), raw.currentState());
        return new Snapshot(state, time);
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
            String currentState = snapshot.currentState();
            updateState(CHANNEL_CURRENT_STATE, currentState == null ? UnDefType.UNDEF : new StringType(currentState));
            updateState(CHANNEL_HOME, HomeSelection.reported(snapshot.home()));
            publishPause(snapshot.pause());
            vacation.report(snapshot.vacation());
            publishVacation();
            Map<String, String> properties = new HashMap<>(getThing().getProperties());
            String vendor = snapshot.brandingType();
            String serial = snapshot.serialNumber();
            String firmware = snapshot.firmware();
            ZonedDateTime systemTime = snapshot.systemTime();
            if (vendor != null) {
                properties.put(Thing.PROPERTY_VENDOR, vendor);
            }
            if (serial != null) {
                properties.put(Thing.PROPERTY_SERIAL_NUMBER, serial);
            }
            if (firmware != null) {
                properties.put(Thing.PROPERTY_FIRMWARE_VERSION, firmware);
            }
            if (systemTime != null) {
                properties.put(PROPERTY_SYSTEM_TIME, DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(systemTime));
                if (!clockWarningLogged && Duration.between(systemTime.toInstant(), clock.instant()).abs()
                        .compareTo(Duration.ofMinutes(5)) > 0) {
                    clockWarningLogged = true;
                    logger.warn(
                            "Controller {} reports time {}, which differs from openHAB by more than five minutes; check the controller clock",
                            getThing().getUID(), systemTime);
                }
            }
            updateProperties(properties);
            updateStatus(ThingStatus.ONLINE);
            for (Thing child : getThing().getThings()) {
                publishChild(child.getHandler(), snapshot);
            }
            RoomDiscoveryService service = discovery;
            if (service != null) {
                service.publish(snapshot);
            }
        }
    }
}
