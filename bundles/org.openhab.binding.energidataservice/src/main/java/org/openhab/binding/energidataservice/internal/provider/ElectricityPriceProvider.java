/**
 * Copyright (c) 2010-2024 Contributors to the openHAB project
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
package org.openhab.binding.energidataservice.internal.provider;

import static org.openhab.binding.energidataservice.internal.EnergiDataServiceBindingConstants.DAILY_REFRESH_TIME_CET;
import static org.openhab.binding.energidataservice.internal.EnergiDataServiceBindingConstants.NORD_POOL_TIMEZONE;
import static org.openhab.binding.energidataservice.internal.EnergiDataServiceBindingConstants.PROPERTY_DATETIME_FORMAT;
import static org.openhab.binding.energidataservice.internal.EnergiDataServiceBindingConstants.PROPERTY_NEXT_CALL;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.http.HttpStatus;
import org.openhab.binding.energidataservice.internal.ApiController;
import org.openhab.binding.energidataservice.internal.CacheManager;
import org.openhab.binding.energidataservice.internal.ElectricityPriceListener;
import org.openhab.binding.energidataservice.internal.api.DateQueryParameter;
import org.openhab.binding.energidataservice.internal.api.DateQueryParameterType;
import org.openhab.binding.energidataservice.internal.api.dto.ElspotpriceRecord;
import org.openhab.binding.energidataservice.internal.exception.DataServiceException;
import org.openhab.binding.energidataservice.internal.retry.RetryPolicyFactory;
import org.openhab.binding.energidataservice.internal.retry.RetryStrategy;
import org.openhab.core.common.ThreadPoolManager;
import org.openhab.core.i18n.TimeZoneProvider;
import org.openhab.core.io.net.http.HttpClientFactory;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link ElectricityPriceProvider} is responsible for fetching electricity
 * prices and providing them to listeners.
 *
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
@Component(service = ElectricityPriceProvider.class)
public class ElectricityPriceProvider {

    private final Logger logger = LoggerFactory.getLogger(ElectricityPriceProvider.class);
    private final ScheduledExecutorService scheduler = ThreadPoolManager.getScheduledPool("thingHandler");
    private final TimeZoneProvider timeZoneProvider;
    private final ApiController apiController;
    private final Map<ElectricityPriceListener, Set<Subscription>> listenerToSubscriptions = new ConcurrentHashMap<>();
    private final Map<Subscription, Set<ElectricityPriceListener>> subscriptionToListeners = new ConcurrentHashMap<>();

    private @Nullable ScheduledFuture<?> refreshFuture;
    private @Nullable ScheduledFuture<?> priceUpdateFuture;
    private RetryStrategy retryPolicy = RetryPolicyFactory.initial();

    @Activate
    public ElectricityPriceProvider(final @Reference HttpClientFactory httpClientFactory,
            final @Reference TimeZoneProvider timeZoneProvider) {
        this.timeZoneProvider = timeZoneProvider;
        this.apiController = new ApiController(httpClientFactory.getCommonHttpClient(), timeZoneProvider);
    }

    public void subscribe(ElectricityPriceListener listener, Subscription subscription) {
        Set<Subscription> subscriptionsForListener = listenerToSubscriptions.computeIfAbsent(listener,
                k -> ConcurrentHashMap.newKeySet());

        if (subscriptionsForListener.contains(subscription)) {
            throw new IllegalStateException(
                    "Duplicate listener registration for " + listener.getClass().getName() + ": " + subscription);
        }

        subscriptionsForListener.add(subscription);

        boolean isFirstSubscription = subscriptionToListeners.isEmpty();
        subscriptionToListeners.computeIfAbsent(subscription, k -> ConcurrentHashMap.newKeySet()).add(listener);

        if (isFirstSubscription) {
            logger.trace("First subscriber, start job");
            refreshFuture = scheduler.schedule(this::refreshElectricityPrices, 0, TimeUnit.SECONDS);
        } else if (subscription instanceof SpotPriceSubscription spotPriceSubscription) {
            // FIXME: Support datahub subscription as well
            publishCurrentSpotPriceFromCache(spotPriceSubscription);
            publishSpotPricesFromCache(spotPriceSubscription);
        }
    }

    public void unsubscribe(ElectricityPriceListener listener, Subscription subscription) {
        Set<Subscription> listenerSubscriptions = listenerToSubscriptions.get(listener);

        if (listenerSubscriptions == null || !listenerSubscriptions.contains(subscription)) {
            throw new IllegalArgumentException(
                    "Listener is not subscribed to the specified subscription: " + subscription);
        }

        listenerSubscriptions.remove(subscription);

        // If the listener has no more subscriptions, remove the listener from the map
        if (listenerSubscriptions.isEmpty()) {
            listenerToSubscriptions.remove(listener);
        }

        Set<ElectricityPriceListener> listenersForSubscription = subscriptionToListeners.get(subscription);

        if (listenersForSubscription != null) {
            // Remove the listener from the subscription's set
            listenersForSubscription.remove(listener);

            // If the subscription has no more listeners, remove the subscription from the map
            if (listenersForSubscription.isEmpty()) {
                subscriptionToListeners.remove(subscription);
            }
        }

        if (subscriptionToListeners.isEmpty()) {
            logger.trace("Last subscriber, stop job");
            ScheduledFuture<?> refreshFuture = this.refreshFuture;
            if (refreshFuture != null) {
                refreshFuture.cancel(true);
                this.refreshFuture = null;
            }
        }
    }

    public void unsubscribe(ElectricityPriceListener listener) {
        Set<Subscription> listenerSubscriptions = listenerToSubscriptions.get(listener);
        if (listenerSubscriptions == null) {
            return;
        }
        for (Subscription subscription : listenerSubscriptions) {
            unsubscribe(listener, subscription);
        }
    }

    public void triggerSpotPriceUpdate() {
        for (Subscription subscription : subscriptionToListeners.keySet()) {
            if (subscription instanceof SpotPriceSubscription spotPriceSubscription) {
                publishCurrentSpotPriceFromCache(spotPriceSubscription);
                publishSpotPricesFromCache(spotPriceSubscription);
            }
        }
    }

    private void refreshElectricityPrices() {
        logger.trace("refreshElectricityPrices");
        for (Subscription subscription : subscriptionToListeners.keySet()) {
            if (subscription instanceof SpotPriceSubscription spotPriceSubscription) {
                refreshSpotPrices(spotPriceSubscription);
            }
        }
    }

    private void refreshSpotPrices(SpotPriceSubscription subscription) {
        RetryStrategy retryPolicy;
        try {
            boolean spotPricesDownloaded = downloadSpotPrices(subscription);

            updatePrices(subscription);
            publishSpotPricesFromCache(subscription);

            long numberOfFutureSpotPrices = subscription.cacheManager.getNumberOfFutureSpotPrices();
            LocalTime now = LocalTime.now(NORD_POOL_TIMEZONE);

            if (numberOfFutureSpotPrices >= 13 || (numberOfFutureSpotPrices == 12
                    && now.isAfter(DAILY_REFRESH_TIME_CET.minusHours(1)) && now.isBefore(DAILY_REFRESH_TIME_CET))) {
                if (spotPricesDownloaded) {
                    subscriptionToListeners.getOrDefault(subscription, ConcurrentHashMap.newKeySet())
                            .forEach(listener -> listener.onDayAheadAvailable());
                }
                retryPolicy = RetryPolicyFactory.atFixedTime(DAILY_REFRESH_TIME_CET, NORD_POOL_TIMEZONE);
            } else {
                logger.warn("Spot prices are not available, retry scheduled (see details in Thing properties)");
                retryPolicy = RetryPolicyFactory.whenExpectedSpotPriceDataMissing();
            }
        } catch (DataServiceException e) {
            if (e.getHttpStatus() != 0) {
                subscriptionToListeners.getOrDefault(subscription, ConcurrentHashMap.newKeySet()).forEach(
                        listener -> listener.onCommunicationError(HttpStatus.getCode(e.getHttpStatus()).getMessage()));
            } else {
                subscriptionToListeners.getOrDefault(subscription, ConcurrentHashMap.newKeySet())
                        .forEach(listener -> listener.onCommunicationError(e.getMessage()));
            }
            if (e.getCause() != null) {
                logger.debug("Error retrieving prices", e);
            }
            retryPolicy = RetryPolicyFactory.fromThrowable(e);
        } catch (InterruptedException e) {
            logger.debug("Refresh job interrupted");
            Thread.currentThread().interrupt();
            return;
        }

        reschedulePriceRefreshJob(retryPolicy);
    }

    private boolean downloadSpotPrices(SpotPriceSubscription subscription)
            throws InterruptedException, DataServiceException {
        if (subscription.cacheManager.areSpotPricesFullyCached()) {
            logger.debug("Cached spot prices still valid, skipping download.");
            return false;
        }
        DateQueryParameter start;
        if (subscription.cacheManager.areHistoricSpotPricesCached()) {
            start = DateQueryParameter.of(DateQueryParameterType.UTC_NOW);
        } else {
            start = DateQueryParameter.of(DateQueryParameterType.UTC_NOW,
                    Duration.ofHours(-CacheManager.NUMBER_OF_HISTORIC_HOURS));
        }
        Map<String, String> properties = new HashMap<>();
        try {
            ElspotpriceRecord[] spotPriceRecords = apiController.getSpotPrices(subscription.getPriceArea(),
                    subscription.getCurrency(), start, DateQueryParameter.EMPTY, properties);
            subscription.cacheManager.putSpotPrices(spotPriceRecords, subscription.getCurrency());
        } finally {
            subscriptionToListeners.getOrDefault(subscription, ConcurrentHashMap.newKeySet())
                    .forEach(listener -> listener.onPropertiesUpdated(properties));
        }
        return true;
    }

    private void publishSpotPricesFromCache(SpotPriceSubscription subscription) {
        subscriptionToListeners.getOrDefault(subscription, ConcurrentHashMap.newKeySet()).forEach(listener -> listener
                .onSpotPrices(subscription.cacheManager.getSpotPrices(), subscription.getCurrency()));
    }

    private void updateAllPrices() {
        subscriptionToListeners.keySet().stream().filter(SpotPriceSubscription.class::isInstance)
                .map(SpotPriceSubscription.class::cast).forEach(this::updatePrices);
        reschedulePriceUpdateJob();
    }

    private void updatePrices(SpotPriceSubscription subscription) {
        subscription.cacheManager.cleanup();
        publishCurrentSpotPriceFromCache(subscription);
    }

    private void publishCurrentSpotPriceFromCache(SpotPriceSubscription subscription) {
        BigDecimal spotPrice = subscription.cacheManager.getSpotPrice();
        subscriptionToListeners.getOrDefault(subscription, ConcurrentHashMap.newKeySet())
                .forEach(listener -> listener.onCurrentSpotPrice(spotPrice, subscription.getCurrency()));
    }

    private void reschedulePriceUpdateJob() {
        logger.trace("reschedulePriceUpdateJob");
        ScheduledFuture<?> priceUpdateJob = this.priceUpdateFuture;
        if (priceUpdateJob != null) {
            // Do not interrupt ourselves.
            priceUpdateJob.cancel(false);
            this.priceUpdateFuture = null;
        }

        Instant now = Instant.now();
        long millisUntilNextClockHour = Duration
                .between(now, now.plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.HOURS)).toMillis() + 1;
        this.priceUpdateFuture = scheduler.schedule(this::updateAllPrices, millisUntilNextClockHour,
                TimeUnit.MILLISECONDS);
        logger.debug("Price update job rescheduled in {} milliseconds", millisUntilNextClockHour);
    }

    private void reschedulePriceRefreshJob(RetryStrategy retryPolicy) {
        // Preserve state of previous retry policy when configuration is the same.
        if (!retryPolicy.equals(this.retryPolicy)) {
            this.retryPolicy = retryPolicy;
        }

        ScheduledFuture<?> refreshJob = this.refreshFuture;

        long secondsUntilNextRefresh = this.retryPolicy.getDuration().getSeconds();
        Instant timeOfNextRefresh = Instant.now().plusSeconds(secondsUntilNextRefresh);
        this.refreshFuture = scheduler.schedule(this::refreshElectricityPrices, secondsUntilNextRefresh,
                TimeUnit.SECONDS);
        logger.debug("Price refresh job rescheduled in {} seconds: {}", secondsUntilNextRefresh, timeOfNextRefresh);
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(PROPERTY_DATETIME_FORMAT);
        String nextCall = LocalDateTime.ofInstant(timeOfNextRefresh, timeZoneProvider.getTimeZone())
                .truncatedTo(ChronoUnit.SECONDS).format(formatter);
        Map<String, String> propertyMap = Map.of(PROPERTY_NEXT_CALL, nextCall);
        listenerToSubscriptions.keySet().forEach(listener -> listener.onPropertiesUpdated(propertyMap));

        if (refreshJob != null) {
            refreshJob.cancel(true);
        }
    }
}
