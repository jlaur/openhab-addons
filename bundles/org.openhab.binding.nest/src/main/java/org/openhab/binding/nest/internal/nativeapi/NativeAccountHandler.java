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

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.util.HttpCookieStore;
import org.openhab.binding.nest.internal.nativeapi.dto.NativeDtos.Credentials;
import org.openhab.binding.nest.internal.nativeapi.dto.NativeDtos.Protect;
import org.openhab.binding.nest.internal.nativeapi.dto.NativeDtos.Structure;
import org.openhab.core.common.ThreadPoolManager;
import org.openhab.core.io.net.http.HttpClientFactory;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.BaseBridgeHandler;
import org.openhab.core.thing.binding.ThingHandlerService;
import org.openhab.core.types.Command;

/**
 * Owns a serialized account session, its credentials, observations, and device and structure commands.
 *
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
public class NativeAccountHandler extends BaseBridgeHandler {
    private final HttpClientFactory httpFactory;
    private final NativeCredentialStore store;
    private final AtomicLong generation = new AtomicLong();
    private volatile @Nullable AccountRun accountRun;
    private volatile Map<String, Protect> devices = Map.of();
    private volatile Map<String, Structure> structures = Map.of();
    private volatile Map<String, String> rooms = Map.of();
    private volatile boolean refreshRequested;
    private volatile CompletableFuture<@Nullable Void> stopped = CompletableFuture.completedFuture(null);

    private static final class AccountRun {
        final Map<String, PendingCommand> commands = new ConcurrentHashMap<>();
        final AtomicLong commandGeneration = new AtomicLong();
        volatile boolean acceptingCommands;
        volatile long session;
        final ScheduledExecutorService executor;
        volatile @Nullable NativeClient client;
        volatile @Nullable ScheduledFuture<?> scheduled;
        @Nullable
        HttpClient http;

        AccountRun(ScheduledExecutorService executor) {
            this.executor = executor;
        }
    }

    @FunctionalInterface
    private interface AccountCommand {
        void execute(NativeClient api) throws NativeClient.Failure, InterruptedException;
    }

    private record PendingCommand(long session, long connection, AccountCommand command) {
    }

    NativeAccountHandler(Bridge bridge, HttpClientFactory httpFactory, NativeCredentialStore store) {
        super(bridge);
        this.httpFactory = httpFactory;
        this.store = store;
    }

    @Override
    public void initialize() {
        if (accountRun != null) {
            stop(false);
        }
        long session = generation.incrementAndGet();
        ScheduledExecutorService executor = ThreadPoolManager.getPoolBasedSequentialScheduledExecutorService(
                "nest-native", "nest-native-" + getThing().getUID().getId());
        AccountRun context = new AccountRun(executor);
        accountRun = context;
        updateStatus(ThingStatus.UNKNOWN);
        stopped.thenRunAsync(() -> {
            if (!current(session)) {
                return;
            }
            try {
                Credentials credentials = store.load();
                if (!current(session)) {
                    return;
                }
                if (credentials == null) {
                    updateStatus(ThingStatusDetail.AUTHORIZATION_PENDING,
                            "Use the authenticate Thing action to supply browser credentials");
                } else {
                    start(context, session, credentials);
                }
            } catch (IllegalStateException e) {
                if (current(session)) {
                    updateStatus(ThingStatusDetail.CONFIGURATION_ERROR,
                            "Stored authentication state cannot be read; use the authenticate action");
                }
            }
        }, executor);
    }

    boolean authenticate(String url, String cookieHeader) {
        Credentials credentials;
        try {
            credentials = NativeCookies.capture(url, cookieHeader);
        } catch (IllegalArgumentException e) {
            return false;
        }
        AccountRun context = accountRun;
        if (context == null || context.executor.isShutdown()) {
            return false;
        }
        long session = generation.incrementAndGet();
        ScheduledExecutorService executor = context.executor;
        context.commands.clear();
        ScheduledFuture<?> scheduled = context.scheduled;
        if (scheduled != null) {
            scheduled.cancel(false);
        }
        NativeClient previous = context.client;
        if (previous != null) {
            previous.cancel();
        }
        try {
            stopped.thenRunAsync(() -> {
                if (current(session)) {
                    start(context, session, credentials);
                }
            }, executor);
            return true;
        } catch (RejectedExecutionException e) {
            return false;
        }
    }

    private void start(AccountRun context, long session, Credentials credentials) {
        closeHttp(context);
        if (!current(session)) {
            return;
        }
        devices = Map.of();
        structures = Map.of();
        rooms = Map.of();
        context.acceptingCommands = false;
        updateStatus(ThingStatus.UNKNOWN);
        try {
            store.save(credentials);
            HttpClient connection = httpFactory.createHttpClient("nest-native");
            // Cookies are handled explicitly so only the isolated account state is persisted and sent.
            connection.setCookieStore(new HttpCookieStore.Empty());
            context.http = connection;
            connection.start();
            NativeClient api = new NativeClient(connection, credentials, state -> {
                if (current(session)) {
                    store.save(state);
                }
            });
            context.client = api;
            context.session = session;
            if (!current(session)) {
                api.cancel();
                closeHttp(context);
                return;
            }
            run(context, session, api, true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            closeHttp(context);
        } catch (Exception e) {
            closeHttp(context);
            if (current(session)) {
                updateStatus(ThingStatusDetail.COMMUNICATION_ERROR,
                        "Could not initialize or persist the native account session");
            }
        }
    }

    private void run(AccountRun context, long session, NativeClient api, boolean snapshot) {
        if (!current(session)) {
            return;
        }
        long delay = 1;
        boolean retrySnapshot = false;
        try {
            boolean wrote = false;
            for (var entry : context.commands.entrySet()) {
                PendingCommand command = entry.getValue();
                if (context.commands.remove(entry.getKey(), command) && command.session() == session
                        && command.connection() == context.commandGeneration.get()) {
                    if (!current(session)) {
                        return;
                    }
                    command.command().execute(api);
                    wrote = true;
                }
            }
            if (snapshot || refreshRequested || wrote) {
                refreshRequested = false;
                api.refresh();
            } else {
                api.poll();
            }
            if (!current(session)) {
                return;
            }
            devices = api.devices();
            structures = api.structures();
            rooms = api.rooms();
            context.acceptingCommands = true;
            updateStatus(ThingStatus.ONLINE);
            for (var thing : getThing().getThings()) {
                if (!current(session)) {
                    return;
                }
                if (thing.getHandler() instanceof NativeProtectHandler protect) {
                    protect.refreshFromBridge();
                } else if (thing.getHandler() instanceof NativeStructureHandler structure) {
                    structure.refreshFromBridge();
                }
            }
        } catch (NativeClient.Failure e) {
            if (!current(session)) {
                return;
            }
            // Never replay a queued mode change after an outage: the user may have changed it elsewhere.
            context.acceptingCommands = false;
            context.commandGeneration.incrementAndGet();
            context.commands.clear();
            if (e.kind == NativeClient.FailureKind.AUTHENTICATION) {
                updateStatus(ThingStatusDetail.CONFIGURATION_ERROR, e.getMessage());
                return;
            }
            updateStatus(ThingStatusDetail.COMMUNICATION_ERROR, e.getMessage());
            delay = e.retrySeconds;
            retrySnapshot = true;
            api.invalidateSession();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        } catch (RuntimeException e) {
            context.acceptingCommands = false;
            context.commandGeneration.incrementAndGet();
            context.commands.clear();
            if (current(session)) {
                updateStatus(ThingStatusDetail.COMMUNICATION_ERROR,
                        "Could not process or persist account state; use the authenticate action to retry");
            }
            return;
        }
        boolean nextSnapshot = retrySnapshot;
        if (current(session)) {
            try {
                ScheduledFuture<?> scheduled = context.executor.schedule(() -> run(context, session, api, nextSnapshot),
                        delay, TimeUnit.SECONDS);
                context.scheduled = scheduled;
                if (!current(session)) {
                    scheduled.cancel(false);
                }
            } catch (RejectedExecutionException e) {
                /* Disposal stopped the account executor. */ }
        }
    }

    private boolean current(long session) {
        return generation.get() == session && accountRun != null;
    }

    Map<String, Protect> devices() {
        return devices;
    }

    Map<String, Structure> structures() {
        return structures;
    }

    Map<String, String> rooms() {
        return rooms;
    }

    boolean setAway(String id, boolean away) {
        return structures.containsKey(id) && enqueue("structure." + id, api -> api.setAway(id, away));
    }

    boolean setPathlight(String id, NativePathlightMode mode) {
        Protect protect = devices.get(id);
        return protect != null && mode.supported(protect) && enqueue("topaz." + id, api -> api.setPathlight(id, mode));
    }

    private boolean enqueue(String key, AccountCommand command) {
        long session = generation.get();
        AccountRun context = accountRun;
        if (context == null) {
            return false;
        }
        long connection = context.commandGeneration.get();
        if (!context.acceptingCommands || context.session != session || !current(session)
                || getThing().getStatus() != ThingStatus.ONLINE) {
            return false;
        }
        NativeClient api = context.client;
        if (api == null) {
            return false;
        }
        // Bound pending commands to one per known bucket; the latest requested mode wins.
        context.commands.put(key, new PendingCommand(session, connection, command));
        api.wakePoll();
        return true;
    }

    void requestRefresh() {
        refreshRequested = true;
        AccountRun context = accountRun;
        NativeClient api = context == null ? null : context.client;
        if (api != null) {
            api.wakePoll();
        }
    }

    @Override
    public Collection<Class<? extends ThingHandlerService>> getServices() {
        return List.of(NativeAccountActions.class, NativeDiscoveryService.class);
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
    }

    @Override
    public void dispose() {
        stop(false);
    }

    @Override
    public void handleRemoval() {
        stop(true);
        super.handleRemoval();
    }

    private void stop(boolean remove) {
        generation.incrementAndGet();
        AccountRun context = accountRun;
        accountRun = null;
        devices = Map.of();
        structures = Map.of();
        rooms = Map.of();
        if (context != null) {
            context.commands.clear();
            ScheduledFuture<?> scheduled = context.scheduled;
            if (scheduled != null) {
                scheduled.cancel(false);
            }
            NativeClient api = context.client;
            if (api != null) {
                api.cancel();
            }
            CompletableFuture<@Nullable Void> cleanup = new CompletableFuture<>();
            stopped = cleanup;
            context.executor.execute(() -> {
                try {
                    closeHttp(context);
                } finally {
                    cleanup.complete(null);
                }
            });
            context.executor.shutdown();
        }
        if (remove) {
            stopped.thenRun(store::remove);
        }
    }

    private void closeHttp(AccountRun context) {
        HttpClient connection = context.http;
        context.http = null;
        if (connection != null) {
            try {
                connection.stop();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                /* Do not expose credential-bearing transport diagnostics. */ }
        }
    }
}
