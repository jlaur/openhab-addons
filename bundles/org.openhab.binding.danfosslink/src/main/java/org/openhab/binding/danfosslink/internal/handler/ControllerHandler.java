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
import java.time.DateTimeException;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.danfosslink.internal.CloudClient;
import org.openhab.binding.danfosslink.internal.DanfossLinkStateDescriptionProvider;
import org.openhab.binding.danfosslink.internal.PairingStore;
import org.openhab.binding.danfosslink.internal.WorldState;
import org.openhab.binding.danfosslink.internal.action.PairingActions;
import org.openhab.binding.danfosslink.internal.config.ControllerConfiguration;
import org.openhab.binding.danfosslink.internal.discovery.RoomDiscoveryService;
import org.openhab.binding.danfosslink.internal.exception.CloudServerException;
import org.openhab.binding.danfosslink.internal.exception.ControllerConnectionTimeoutException;
import org.openhab.binding.danfosslink.internal.exception.ControllerResponseException;
import org.openhab.binding.danfosslink.internal.exception.ControllerUnavailableException;
import org.openhab.binding.danfosslink.internal.exception.RelayConnectionClosedException;
import org.openhab.binding.danfosslink.internal.protocol.Alarms;
import org.openhab.binding.danfosslink.internal.protocol.ControllerTime;
import org.openhab.binding.danfosslink.internal.protocol.Identity;
import org.openhab.binding.danfosslink.internal.protocol.Operation;
import org.openhab.core.common.ThreadPoolManager;
import org.openhab.core.i18n.TimeZoneProvider;
import org.openhab.core.library.types.DecimalType;
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
    private final DanfossLinkStateDescriptionProvider descriptions;
    private final Clock clock;
    private final TimeZoneProvider timeZoneProvider;
    private volatile TimeZoneProvider controllerTimeZoneProvider;
    private final Object lifecycle = new Object();

    private final PendingCommands pendingCommands = new PendingCommands();
    private HomeSelection homeSelection = new HomeSelection();
    private VacationState vacation = new VacationState();

    private boolean running;
    private boolean clockWarningLogged;
    private @Nullable Operation active;
    private @Nullable ScheduledFuture<?> polling;
    private @Nullable WorldState world;
    private @Nullable RoomDiscoveryService discovery;

    public ControllerHandler(Bridge bridge, PairingStore store, DanfossLinkStateDescriptionProvider descriptions,
            TimeZoneProvider timeZoneProvider) {
        this(bridge, store, descriptions, Clock.systemUTC(), timeZoneProvider);
    }

    ControllerHandler(Bridge bridge, PairingStore store, DanfossLinkStateDescriptionProvider descriptions) {
        this(bridge, store, descriptions, Clock.systemUTC());
    }

    ControllerHandler(Bridge bridge, PairingStore store, DanfossLinkStateDescriptionProvider descriptions,
            Clock clock) {
        this(bridge, store, descriptions, clock, clock::getZone);
    }

    ControllerHandler(Bridge bridge, PairingStore store, DanfossLinkStateDescriptionProvider descriptions, Clock clock,
            TimeZoneProvider timeZoneProvider) {
        super(bridge);
        this.timeZoneProvider = timeZoneProvider;
        this.controllerTimeZoneProvider = timeZoneProvider;
        this.store = store;
        this.descriptions = descriptions;
        this.clock = clock;
    }

    @Override
    public void initialize() {
        var config = getConfigAs(ControllerConfiguration.class);
        int interval = config.refreshInterval;
        if (interval < ControllerConfiguration.MINIMUM_REFRESH_INTERVAL) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "Refresh interval must be at least " + ControllerConfiguration.MINIMUM_REFRESH_INTERVAL
                            + " seconds");
            return;
        }
        TimeZoneProvider selectedTimeZoneProvider = timeZoneProvider;
        try {
            if (!config.timeZone.isBlank()) {
                ZoneId zone = ZoneId.of(config.timeZone);
                selectedTimeZoneProvider = () -> zone;
            }
        } catch (DateTimeException e) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, e.getMessage());
            return;
        }
        synchronized (lifecycle) {
            controllerTimeZoneProvider = selectedTimeZoneProvider;
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
            pendingCommands.clear();
            vacation = new VacationState();
            homeSelection = new HomeSelection();
            world = null;
            descriptions.remove(getThing().getUID());
        }
        if (closing != null) {
            closing.close();
        }
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        if (command == RefreshType.REFRESH) {
            refresh();
        } else if (CHANNEL_VACATION_TEMPERATURE.equals(channelUID.getId())) {
            queueCommand(new PendingCommand.Patch(null, CHANNEL_VACATION_TEMPERATURE, command));
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
                        pendingCommands.add(new PendingCommand.Home(request));
                    } catch (IllegalArgumentException e) {
                        logger.debug("Controller {} rejected Home command: {}", getThing().getUID(), e.getMessage());
                    }
                } else {
                    logger.debug("Controller {} rejected Home command type {}", getThing().getUID(),
                            command.getClass().getSimpleName());
                }
                updateState(CHANNEL_HOME, HomeSelection.reported(snapshot.home()));
                if (!pendingCommands.contains(PendingCommand.Kind.HOME)) {
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
                    pendingCommands.add(
                            new PendingCommand.Pause(PauseState.command(channelUID.getId(), command, clock.instant())));
                } catch (IllegalArgumentException e) {
                    logger.debug("Controller {} rejected pause command: {}", getThing().getUID(), e.getMessage());
                }
                publishPause(snapshot.pause());
                if (!pendingCommands.contains(PendingCommand.Kind.PAUSE)) {
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
                        pendingCommands.add(new PendingCommand.Vacation(request));
                    }
                } catch (IllegalArgumentException e) {
                    logger.debug("Controller {} rejected vacation command: {}", getThing().getUID(), e.getMessage());
                }
                publishVacation();
                if (!pendingCommands.contains(PendingCommand.Kind.VACATION)) {
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
            worker.execute(() -> run(operation, pin, List.of()));
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
            List<PendingCommand> pending = pendingCommands.drain();
            worker.execute(() -> run(operation, null, pending));
        }
    }

    void roomCommand(int roomId, String channel, Command command) {
        queueCommand(new PendingCommand.Patch(roomId, channel, command));
    }

    void livingZoneCommand(String channel, Command command) {
        queueCommand(new PendingCommand.Patch(null, channel, command));
    }

    private void queueCommand(PendingCommand.Patch command) {
        synchronized (lifecycle) {
            WorldState snapshot = world;
            if (!running || snapshot == null) {
                logger.debug("Ignoring command for {}: running={}, controller snapshot available={}", command.key(),
                        running, snapshot != null);
                return;
            }
            try {
                command.patch(snapshot);
            } catch (IllegalArgumentException e) {
                logger.debug("Ignoring invalid command for {}: {}", command.key(), e.getMessage());
                return;
            }
            pendingCommands.add(command);
        }
        refresh();
    }

    CloudClient connect(Identity identity, Operation operation) throws IOException {
        return new CloudClient(identity, operation);
    }

    private void run(Operation operation, @Nullable String pin, List<PendingCommand> pending) {
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
                        boolean refreshRequired = false;
                        for (PendingCommand command : pending) {
                            operation.check();
                            try {
                                var prepared = command.prepare(snapshot.state(), clock.instant(), snapshot.time());
                                stage = "writing " + prepared.uri();
                                logger.trace("Controller {} applying {}", getThing().getUID(), command);
                                prepared.write().send(session);
                                if (command.readBackImmediately()) {
                                    stage = "reading back " + prepared.uri();
                                    snapshot = readWorld(session);
                                } else {
                                    refreshRequired = true;
                                }
                            } catch (IllegalArgumentException e) {
                                logger.debug("Controller {} rejected command {} after refresh: {}", getThing().getUID(),
                                        command.key(), e.getMessage());
                            } catch (ControllerResponseException e) {
                                if (!command.recoverRejection()) {
                                    throw e;
                                }
                                logger.debug("Controller {} rejected request {} with HTTP {}", getThing().getUID(),
                                        command.key(), e.status());
                                stage = "reading state after rejected " + command.key().kind() + " request";
                                snapshot = readWorld(session);
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
        } catch (IOException e) {
            synchronized (lifecycle) {
                if (current(operation)) {
                    for (String channel : VACATION_CHANNELS) {
                        updateState(channel, UnDefType.UNDEF);
                    }
                    publishPause(null);
                    publishAlarms(null);
                    updateState(CHANNEL_HOME, UnDefType.UNDEF);
                    updateState(CHANNEL_CURRENT_STATE, UnDefType.UNDEF);
                    updateState(CHANNEL_VACATION_TEMPERATURE, UnDefType.UNDEF);
                    logger.debug("Controller {} operation failed during {}", getThing().getUID(), stage, e);
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, pin == null ? switch (e) {
                        case ControllerUnavailableException unavailable -> unavailable.getMessage() + "; will retry";
                        case CloudServerException server -> server.getMessage() + "; will retry";
                        case ControllerConnectionTimeoutException timeout -> timeout.getMessage() + "; will retry";
                        case RelayConnectionClosedException closed -> closed.getMessage() + "; will retry";
                        case ControllerResponseException rejection ->
                            "Controller returned HTTP " + rejection.status() + " while " + stage;
                        default -> "Cloud communication failed while " + stage + "; will retry";
                    } : "Pairing failed; obtain a fresh code and retry the pair action");
                }
            }
        } finally {
            boolean again;
            synchronized (lifecycle) {
                again = current(operation) && (paired || !pendingCommands.isEmpty());
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
        WorldState raw = session.world(controllerTimeZoneProvider.getTimeZone());
        // Keep each conversion with its own response; never reuse a skew after reconnect or clock correction.
        ControllerTime time = ControllerTime.observed(raw.systemTime(), clock.instant());
        var state = new WorldState(raw.rooms(), raw.unit(), raw.brandingType(), raw.serialNumber(), raw.firmware(),
                raw.systemTime(), time.toOpenHab(raw.vacation()), time.toOpenHab(raw.pause()),
                time.toOpenHab(raw.home()), raw.currentState(), raw.vacationTemperature(), raw.alarms());
        return new Snapshot(state, time);
    }

    private boolean current(Operation operation) {
        return running && operation.equals(active);
    }

    private void publishAlarms(@Nullable Alarms alarms) {
        updateState(CHANNEL_ALARM, alarms == null ? UnDefType.UNDEF : OnOffType.from(alarms.count() > 0));
        updateState(CHANNEL_ALARM_COUNT, alarms == null ? UnDefType.UNDEF : new DecimalType(alarms.count()));
        updateState(CHANNEL_ALARMS, alarms == null ? UnDefType.UNDEF : new StringType(alarms.json()));
    }

    private void publish(Operation operation, WorldState snapshot) {
        // Status and state publication share the lifecycle gate with disposal; no network I/O runs under this gate.
        synchronized (lifecycle) {
            if (!current(operation)) {
                return;
            }
            world = snapshot;
            publishAlarms(snapshot.alarms());
            var temperature = snapshot.vacationTemperature();
            descriptions.update(new ChannelUID(getThing().getUID(), CHANNEL_VACATION_TEMPERATURE),
                    temperature == null ? null : temperature.minimum(),
                    temperature == null ? null : temperature.maximum(), snapshot.unit());
            updateState(CHANNEL_VACATION_TEMPERATURE, snapshot.vacationTemperatureState());
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
