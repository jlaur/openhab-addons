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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.veluxactive.internal.VeluxActiveApi;
import org.openhab.binding.veluxactive.internal.api.ModuleInfo;
import org.openhab.binding.veluxactive.internal.api.ModuleType;
import org.openhab.binding.veluxactive.internal.api.dto.serialization.VeluxActiveTokenResponseDeserializer;
import org.openhab.binding.veluxactive.internal.config.VeluxActiveAccountConfiguration;
import org.openhab.binding.veluxactive.internal.discovery.VeluxActiveDiscoveryService;
import org.openhab.binding.veluxactive.internal.exception.ApiException;
import org.openhab.binding.veluxactive.internal.exception.ApiUnauthorizedException;
import org.openhab.core.auth.client.oauth2.AccessTokenResponse;
import org.openhab.core.auth.client.oauth2.OAuthClientService;
import org.openhab.core.auth.client.oauth2.OAuthException;
import org.openhab.core.auth.client.oauth2.OAuthFactory;
import org.openhab.core.auth.client.oauth2.OAuthResponseException;
import org.openhab.core.common.ThreadPoolManager;
import org.openhab.core.i18n.TimeZoneProvider;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.BaseBridgeHandler;
import org.openhab.core.thing.binding.ThingHandlerService;
import org.openhab.core.types.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.GsonBuilder;

/**
 * Authenticates a VELUX ACTIVE account using the core OAuth token store.
 *
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
public class VeluxActiveAccountHandler extends BaseBridgeHandler {
    private static final long RETRY_SECONDS = 60;

    private final Logger logger = LoggerFactory.getLogger(VeluxActiveAccountHandler.class);
    private final OAuthFactory oAuthFactory;
    private final Executor executor;
    private final VeluxActiveApi api;
    private final TimeZoneProvider timeZoneProvider;
    private @Nullable String accessToken;
    private @Nullable ScheduledFuture<?> scheduledPoll;
    private volatile Map<String, String> gatewayHomes = Map.of();
    private boolean running;
    private boolean pollQueued;
    private boolean pollRequested;
    private int fastPollsRemaining;
    private final ScheduledExecutorService authenticationScheduler;

    // Lifecycle decisions and status publication use the handler monitor; network I/O never holds it.
    private long generation;
    private CompletableFuture<@Nullable Void> work = CompletableFuture.completedFuture(null);
    private @Nullable ScheduledFuture<?> scheduledAuthentication;

    // Only the serialized worker accesses the OAuth service and its configuration.
    private @Nullable OAuthClientService client;
    private @Nullable String handle;
    private @Nullable VeluxActiveAccountConfiguration previousConfiguration;

    public VeluxActiveAccountHandler(Bridge bridge, OAuthFactory oAuthFactory, VeluxActiveApi api,
            TimeZoneProvider timeZoneProvider) {
        super(bridge);
        this.oAuthFactory = oAuthFactory;
        this.api = api;
        this.timeZoneProvider = timeZoneProvider;
        this.executor = ThreadPoolManager.getPool("veluxactive");
        this.authenticationScheduler = scheduler;
    }

    VeluxActiveAccountHandler(Bridge bridge, OAuthFactory oAuthFactory, Executor executor,
            ScheduledExecutorService authenticationScheduler, VeluxActiveApi api, TimeZoneProvider timeZoneProvider) {
        super(bridge);
        this.oAuthFactory = oAuthFactory;
        this.api = api;
        this.timeZoneProvider = timeZoneProvider;
        this.executor = executor;
        this.authenticationScheduler = authenticationScheduler;
    }

    @Override
    public synchronized void initialize() {
        gatewayHomes = Map.of();
        running = true;
        long session = ++generation;
        cancelPolling();
        cancelScheduledAuthentication();
        VeluxActiveAccountConfiguration configuration = getConfigAs(VeluxActiveAccountConfiguration.class);
        updateStatus(ThingStatus.UNKNOWN);
        enqueue(session, () -> start(session, configuration));
    }

    private void start(long session, VeluxActiveAccountConfiguration configuration) {
        VeluxActiveAccountConfiguration previous = previousConfiguration;
        release(previous != null && !previous.hasSameCredentials(configuration));
        if (!isCurrent(session)) {
            return;
        }
        previousConfiguration = configuration;
        if (configuration.refreshInterval < 10) {
            finish(session, configuration, ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "@text/offline.conf-error.account.refresh-interval", 0);
            return;
        }
        if (!configuration.isComplete()) {
            finish(session, configuration, ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "@text/offline.conf-error.account.credentials-required", 0);
            return;
        }
        // Include the account identity so editing a Thing while openHAB is stopped cannot reuse another user's token.
        String identity = configuration.clientId + "\n" + configuration.username;
        String serviceHandle = getThing().getUID() + ":"
                + UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
        OAuthClientService service = oAuthFactory
                .createOAuthClientService(serviceHandle, TOKEN_URL, null, configuration.clientId,
                        configuration.clientSecret, SCOPE, false)
                .withGsonBuilder(new GsonBuilder().registerTypeAdapter(AccessTokenResponse.class,
                        new VeluxActiveTokenResponseDeserializer()));
        handle = serviceHandle;
        client = service;
        service.addExtraAuthField("user_prefix", "velux");
        service.addExtraAuthField("app_version", APP_VERSION);
        authenticate(session, configuration);
    }

    private void authenticate(long session, VeluxActiveAccountConfiguration configuration) {
        authenticate(session, configuration, false);
    }

    private void authenticate(long session, VeluxActiveAccountConfiguration configuration, boolean forceRefresh) {
        OAuthClientService service = client;
        if (service == null || !isCurrent(session)) {
            return;
        }
        try {
            AccessTokenResponse response;
            try {
                response = forceRefresh ? service.refreshToken() : service.getAccessTokenResponse();
                if (response != null && response.isExpired(Instant.now(), refreshMargin(response))) {
                    String refreshToken = response.getRefreshToken();
                    response = refreshToken == null || refreshToken.isBlank() ? null : service.refreshToken();
                }
            } catch (OAuthResponseException e) {
                if (!"invalid_grant".equals(e.getError())) {
                    throw e;
                }
                // A revoked refresh token needs a new password grant, not another refresh attempt.
                response = null;
            }
            if (!isCurrent(session)) {
                return;
            }
            if (response == null) {
                response = service.getAccessTokenByResourceOwnerPasswordCredentials(configuration.username,
                        configuration.password, SCOPE);
            }
            String accessToken = response.getAccessToken();
            Instant createdOn = response.getCreatedOn();
            if (accessToken == null || accessToken.isBlank() || createdOn == null || response.getExpiresIn() <= 0) {
                throw new OAuthException("Incomplete token response");
            }
            this.accessToken = accessToken;
            long remaining = Duration.between(Instant.now(), createdOn.plusSeconds(response.getExpiresIn()))
                    .getSeconds();
            finish(session, configuration, ThingStatus.ONLINE, ThingStatusDetail.NONE, null,
                    Math.max(1, remaining - refreshMargin(response)));
        } catch (OAuthResponseException e) {
            accessToken = null;
            boolean configurationError = switch (e.getError()) {
                case "invalid_client", "invalid_grant", "invalid_scope", "unauthorized_client", "access_denied",
                        "unsupported_grant_type", "invalid_request" ->
                    true;
                default -> false;
            };
            finish(session, configuration, ThingStatus.OFFLINE,
                    configurationError ? ThingStatusDetail.CONFIGURATION_ERROR : ThingStatusDetail.COMMUNICATION_ERROR,
                    configurationError ? "@text/offline.conf-error.account.authentication-rejected"
                            : "@text/offline.comm-error.account.authentication-unavailable",
                    configurationError ? 0 : RETRY_SECONDS);
        } catch (IOException | OAuthException e) {
            accessToken = null;
            logger.debug("VELUX ACTIVE authentication failure ({})", e.getClass().getSimpleName());
            boolean interrupted = Thread.currentThread().isInterrupted();
            for (Throwable cause = e; cause != null; cause = cause.getCause()) {
                if (cause instanceof InterruptedException) {
                    interrupted = true;
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            finish(session, configuration, ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                    "@text/offline.comm-error.account.authentication-error", interrupted ? 0 : RETRY_SECONDS);
        }
    }

    private static int refreshMargin(AccessTokenResponse response) {
        return (int) Math.min(60, Math.max(0, response.getExpiresIn() / 10));
    }

    private synchronized void finish(long session, VeluxActiveAccountConfiguration configuration, ThingStatus status,
            ThingStatusDetail detail, @Nullable String description, long delaySeconds) {
        if (session != generation) {
            return;
        }
        cancelScheduledAuthentication();
        updateStatus(status, detail, description);
        if (status == ThingStatus.ONLINE && !getThing().getThings().isEmpty()) {
            requestPoll();
        }
        if (session == generation && delaySeconds > 0) {
            scheduledAuthentication = authenticationScheduler
                    .schedule(() -> queueAuthentication(session, configuration), delaySeconds, TimeUnit.SECONDS);
        }
    }

    private synchronized void queueAuthentication(long session, VeluxActiveAccountConfiguration configuration) {
        if (session == generation) {
            scheduledAuthentication = null;
            enqueue(session, () -> authenticate(session, configuration));
        }
    }

    private synchronized boolean isCurrent(long session) {
        return session == generation;
    }

    private void enqueue(long session, Runnable task) {
        // Cleanup follows in-flight requests, preventing a late response from restoring deleted token storage.
        work = work.handleAsync((ignored, failure) -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                logger.error("Unexpected VELUX ACTIVE authentication failure ({})", e.getClass().getSimpleName());
                synchronized (this) {
                    if (session == generation) {
                        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                                "@text/offline.comm-error.account.unexpected-authentication-error");
                    }
                }
            }
            return null;
        }, executor);
    }

    private void cancelScheduledAuthentication() {
        ScheduledFuture<?> scheduled = scheduledAuthentication;
        scheduledAuthentication = null;
        if (scheduled != null) {
            scheduled.cancel(false);
        }
    }

    private void release(boolean delete) {
        String serviceHandle = handle;
        client = null;
        accessToken = null;
        if (serviceHandle != null) {
            if (delete) {
                oAuthFactory.deleteServiceAndAccessToken(serviceHandle);
                handle = null;
            } else {
                oAuthFactory.ungetOAuthService(serviceHandle);
            }
        }
    }

    @Override
    public synchronized void dispose() {
        gatewayHomes = Map.of();
        running = false;
        long session = ++generation;
        cancelPolling();
        cancelScheduledAuthentication();
        enqueue(session, () -> release(false));
    }

    @Override
    public synchronized void handleRemoval() {
        gatewayHomes = Map.of();
        running = false;
        long session = ++generation;
        cancelPolling();
        cancelScheduledAuthentication();
        enqueue(session, () -> release(true));
        super.handleRemoval();
    }

    @Override
    public Collection<Class<? extends ThingHandlerService>> getServices() {
        return Set.of(VeluxActiveDiscoveryService.class);
    }

    public synchronized void discover(Consumer<List<ModuleInfo>> consumer) {
        if (!running) {
            return;
        }
        long session = generation;
        enqueue(session, () -> {
            String token = accessToken;
            if (token == null || !isCurrent(session)) {
                return;
            }
            try {
                var modules = api.modules(token);
                synchronized (this) {
                    if (isCurrent(session)) {
                        consumer.accept(modules);
                    }
                }
            } catch (ApiException e) {
                logger.debug("Module discovery failed: {}", e.getMessage());
                recoverAuthentication(session, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    synchronized void requestPoll() {
        if (!running) {
            return;
        }
        if (pollQueued) {
            pollRequested = true;
            return;
        }
        ScheduledFuture<?> scheduled = scheduledPoll;
        scheduledPoll = null;
        if (scheduled != null) {
            scheduled.cancel(false);
        }
        pollQueued = true;
        long session = generation;
        enqueue(session, () -> pollModules(session));
    }

    private void pollModules(long session) {
        var targets = new java.util.ArrayList<ModuleRequestContext>();
        try {
            if (!isCurrent(session) || accessToken == null) {
                return;
            }
            for (var thing : getThing().getThings()) {
                collectTargets(thing, targets);
            }
            if (!targets.isEmpty()) {
                refreshTopology(session, targets);
                targets.clear();
                for (var thing : getThing().getThings()) {
                    collectTargets(thing, targets);
                }
            }
            var byHome = targets.stream().filter(target -> !target.homeId().isBlank())
                    .collect(Collectors.groupingBy(ModuleRequestContext::homeId));
            for (var entry : byHome.entrySet()) {
                String token = accessToken;
                if (!isCurrent(session) || token == null) {
                    return;
                }
                try {
                    var status = api.homeStatus(token, entry.getKey());
                    synchronized (this) {
                        if (isCurrent(session)) {
                            entry.getValue().forEach(target -> target.handler().update(target, status));
                        }
                    }
                } catch (ApiException e) {
                    synchronized (this) {
                        if (isCurrent(session)) {
                            entry.getValue().forEach(target -> target.handler().failed(target,
                                    "@text/offline.comm-error.module.status-error"));
                        }
                    }
                    recoverAuthentication(session, e);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            synchronized (this) {
                if (isCurrent(session)) {
                    pollQueued = false;
                    if (!targets.isEmpty() && accessToken != null && !Thread.currentThread().isInterrupted()) {
                        long delay = pollRequested ? 0
                                : fastPollsRemaining > 0 ? 5
                                        : getConfigAs(VeluxActiveAccountConfiguration.class).refreshInterval;
                        if (fastPollsRemaining > 0) {
                            fastPollsRemaining--;
                        }
                        pollRequested = false;
                        scheduledPoll = authenticationScheduler.schedule(() -> {
                            synchronized (this) {
                                if (isCurrent(session)) {
                                    requestPoll();
                                }
                            }
                        }, delay, TimeUnit.SECONDS);
                    }
                }
            }
        }
    }

    private void collectTargets(Thing thing, List<ModuleRequestContext> targets) {
        if (thing.getHandler() instanceof VeluxActiveModuleHandler module) {
            var target = module.captureRequestContext();
            if (target != null) {
                targets.add(target);
            }
        }
        if (thing instanceof Bridge bridge) {
            for (var child : bridge.getThings()) {
                collectTargets(child, targets);
            }
        }
    }

    private void refreshTopology(long session, List<ModuleRequestContext> targets) throws InterruptedException {
        String token = accessToken;
        if (token == null || !isCurrent(session)) {
            return;
        }
        try {
            var modules = api.modules(token);
            synchronized (this) {
                if (isCurrent(session)) {
                    Map<String, String> homes = new HashMap<>();
                    for (var module : modules) {
                        if (module.type() == ModuleType.GATEWAY) {
                            homes.merge(module.moduleId(), module.homeId(),
                                    (first, second) -> first.equals(second) ? first : "");
                        }
                    }
                    gatewayHomes = Map.copyOf(homes);
                    for (var target : targets) {
                        if (target.handler() instanceof VeluxActiveGatewayHandler gateway) {
                            gateway.homeResolved(!target.homeId().equals(homeId(target.moduleId())));
                        }
                        if (target.handler() instanceof VeluxActiveIndoorSensorHandler sensor) {
                            var current = sensor.captureRequestContext();
                            if (current != null) {
                                sensor.updateTopology(current, modules);
                            }
                        }
                    }
                }
            }
        } catch (ApiException e) {
            logger.debug("Could not refresh module topology: {}", e.getMessage());
            recoverAuthentication(session, e);
        }
    }

    String homeId(String moduleId) {
        return gatewayHomes.getOrDefault(moduleId, "");
    }

    void lock(ModuleRequestContext target) {
        if (target.handler() instanceof VeluxActiveGatewayHandler gateway) {
            sendCommand(target, (token, timezone) -> api.lock(token, target.homeId(), target.moduleId(), timezone),
                    () -> gateway.lockAccepted(target));
        }
    }

    void unlock(ModuleRequestContext target) {
        if (target.handler() instanceof VeluxActiveGatewayHandler gateway) {
            sendCommand(target, (token, timezone) -> {
                var signature = gateway.sign(target, target.moduleId(), "scenario", "home", api.signingTime());
                api.unlock(token, target.homeId(), target.moduleId(), timezone, signature);
            }, () -> gateway.unlockAccepted(target));
        }
    }

    void setSignedPosition(ModuleRequestContext target, int position) {
        ModuleRequestContext parent = target.parent();
        if (parent != null && parent.handler() instanceof VeluxActiveGatewayHandler gateway
                && target.handler() instanceof VeluxActiveOpeningHandler opening) {
            sendCommand(target, (token, timezone) -> {
                var signature = gateway.sign(parent, target.moduleId(), "target_position", position, api.signingTime());
                api.setSignedPosition(token, target.homeId(), target.moduleId(), parent.moduleId(), position, timezone,
                        signature);
            }, () -> opening.positionAccepted(target, position));
        }
    }

    synchronized void retrieveKey(ModuleRequestContext target, Runnable accepted, Runnable rejected) {
        if (!running) {
            rejected.run();
            return;
        }
        long session = generation;
        enqueue(session, () -> {
            String token = accessToken;
            if (token == null || !isCurrent(session) || target.homeId().isBlank()
                    || !target.handler().isRequestContextCurrent(target)) {
                rejected.run();
                return;
            }
            try {
                api.retrieveKey(token, target.homeId(), target.moduleId(), timeZoneProvider.getTimeZone().getId());
                synchronized (this) {
                    if (isCurrent(session) && target.handler().isRequestContextCurrent(target)) {
                        accepted.run();
                    } else {
                        rejected.run();
                    }
                }
            } catch (ApiException e) {
                rejected.run();
                recoverAuthentication(session, e);
            } catch (InterruptedException e) {
                rejected.run();
                Thread.currentThread().interrupt();
            }
        });
    }

    void setPosition(ModuleRequestContext target, String gatewayId, int position) {
        if (target.handler() instanceof VeluxActiveOpeningHandler opening) {
            sendCommand(target, (token, timezone) -> api.setPosition(token, target.homeId(), target.moduleId(),
                    gatewayId, position, timezone), () -> opening.positionAccepted(target, position));
        }
    }

    void stopMovements(ModuleRequestContext target, String gatewayId) {
        sendCommand(target, (token, timezone) -> api.stopMovements(token, target.homeId(), gatewayId, timezone), () -> {
        });
    }

    private synchronized void sendCommand(ModuleRequestContext target, ModuleCommand command, Runnable accepted) {
        if (!running) {
            return;
        }
        long session = generation;
        enqueue(session, () -> {
            String token = accessToken;
            if (token == null || !isCurrent(session) || target.homeId().isBlank()
                    || !target.handler().isRequestContextCurrent(target)) {
                return;
            }
            try {
                command.execute(token, timeZoneProvider.getTimeZone().getId());
                synchronized (this) {
                    if (isCurrent(session) && target.handler().isRequestContextCurrent(target)) {
                        accepted.run();
                        fastPollsRemaining = 6;
                        requestPoll();
                    }
                }
            } catch (ApiException e) {
                synchronized (this) {
                    if (isCurrent(session)) {
                        target.handler().failed(target, "@text/offline.comm-error.module.command-error");
                    }
                }
                recoverAuthentication(session, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    @FunctionalInterface
    private interface ModuleCommand {
        void execute(String token, String timezone) throws ApiException, InterruptedException;
    }

    private void recoverAuthentication(long session, ApiException error) {
        VeluxActiveAccountConfiguration configuration = previousConfiguration;
        if (error instanceof ApiUnauthorizedException && configuration != null && isCurrent(session)) {
            authenticate(session, configuration, true);
        }
    }

    private void cancelPolling() {
        ScheduledFuture<?> scheduled = scheduledPoll;
        scheduledPoll = null;
        if (scheduled != null) {
            scheduled.cancel(false);
        }
        pollQueued = false;
        pollRequested = false;
        fastPollsRemaining = 0;
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        // The account bridge has no channels.
    }
}
