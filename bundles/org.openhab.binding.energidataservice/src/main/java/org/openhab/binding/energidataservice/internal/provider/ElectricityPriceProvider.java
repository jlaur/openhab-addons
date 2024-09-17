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

import static org.openhab.binding.energidataservice.internal.EnergiDataServiceBindingConstants.*;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.http.HttpStatus;
import org.openhab.binding.energidataservice.internal.ApiController;
import org.openhab.binding.energidataservice.internal.CacheManager;
import org.openhab.binding.energidataservice.internal.DatahubTariff;
import org.openhab.binding.energidataservice.internal.api.ChargeType;
import org.openhab.binding.energidataservice.internal.api.DateQueryParameter;
import org.openhab.binding.energidataservice.internal.api.DateQueryParameterType;
import org.openhab.binding.energidataservice.internal.api.GlobalLocationNumber;
import org.openhab.binding.energidataservice.internal.api.dto.DatahubPricelistRecord;
import org.openhab.binding.energidataservice.internal.api.dto.ElspotpriceRecord;
import org.openhab.binding.energidataservice.internal.exception.DataServiceException;
import org.openhab.binding.energidataservice.internal.provider.listener.ElectricityPriceListener;
import org.openhab.binding.energidataservice.internal.provider.subscription.DatahubPriceSubscription;
import org.openhab.binding.energidataservice.internal.provider.subscription.ElectricityPriceSubscription;
import org.openhab.binding.energidataservice.internal.provider.subscription.SpotPriceSubscription;
import org.openhab.binding.energidataservice.internal.provider.subscription.Subscription;
import org.openhab.binding.energidataservice.internal.retry.RetryPolicyFactory;
import org.openhab.binding.energidataservice.internal.retry.RetryStrategy;
import org.openhab.core.i18n.TimeZoneProvider;
import org.openhab.core.io.net.http.HttpClientFactory;
import org.openhab.core.scheduler.Scheduler;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link ElectricityPriceProvider} is responsible for fetching electricity
 * prices and providing them to subscribed listeners.
 *
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
@Component(service = ElectricityPriceProvider.class)
public class ElectricityPriceProvider extends AbstractProvider<ElectricityPriceListener> {

    private final Logger logger = LoggerFactory.getLogger(ElectricityPriceProvider.class);
    private final TimeZoneProvider timeZoneProvider;
    private final Scheduler scheduler;
    private final ApiController apiController;
    private final Map<Subscription, CacheManager> subscriptionCaches = new ConcurrentHashMap<>();

    private @Nullable ScheduledFuture<?> refreshFuture;
    private @Nullable ScheduledFuture<?> priceUpdateFuture;
    private RetryStrategy retryPolicy = RetryPolicyFactory.initial();

    @Activate
    public ElectricityPriceProvider(final @Reference Scheduler scheduler,
            final @Reference HttpClientFactory httpClientFactory, final @Reference TimeZoneProvider timeZoneProvider) {
        this.scheduler = scheduler;
        this.timeZoneProvider = timeZoneProvider;
        this.apiController = new ApiController(httpClientFactory.getCommonHttpClient(), timeZoneProvider);
    }

    @Deactivate
    public void deactivate() {
        stopJobs();
    }

    public void subscribe(ElectricityPriceListener listener, Subscription subscription) {
        if (!(subscription instanceof ElectricityPriceSubscription)) {
            throw new IllegalArgumentException(subscription.getClass().getName() + " is not supported");
        }
        boolean isFirstDistinctSubscription = subscribeInternal(listener, subscription);

        subscriptionCaches.putIfAbsent(subscription, new CacheManager());

        if (isFirstDistinctSubscription) {
            ScheduledFuture<?> refreshFuture = this.refreshFuture;
            if (refreshFuture != null) {
                refreshFuture.cancel(true);
            }
            this.refreshFuture = scheduler.at(this::refreshElectricityPrices, Instant.now());
        } else {
            publishCurrentPriceFromCache(subscription, Set.of(listener));
            publishPricesFromCache(subscription, Set.of(listener));
        }
    }

    public void unsubscribe(ElectricityPriceListener listener, Subscription subscription) {
        boolean isLastDistinctSubscription = unsubscribeInternal(listener, subscription);
        if (isLastDistinctSubscription) {
            subscriptionCaches.remove(subscription);
        }

        if (subscriptionToListeners.isEmpty()) {
            logger.trace("Last subscriber, stop jobs");
            stopJobs();
        }
    }

    private void stopJobs() {
        ScheduledFuture<?> refreshFuture = this.refreshFuture;
        if (refreshFuture != null) {
            refreshFuture.cancel(true);
            this.refreshFuture = null;
        }

        ScheduledFuture<?> priceUpdateFuture = this.priceUpdateFuture;
        if (priceUpdateFuture != null) {
            priceUpdateFuture.cancel(true);
            this.priceUpdateFuture = null;
        }
    }

    private CacheManager getCacheManager(Subscription subscription) {
        return Objects.requireNonNullElse(subscriptionCaches.get(subscription), new CacheManager());
    }

    private void refreshElectricityPrices() {
        RetryStrategy retryPolicy;
        try {
            Set<ElectricityPriceListener> spotPricesUpdatedListeners = new HashSet<>();
            boolean spotPricesSubscribed = false;
            long numberOfFutureSpotPrices = 0;

            for (Entry<Subscription, Set<ElectricityPriceListener>> subscriptionListener : subscriptionToListeners
                    .entrySet()) {
                Subscription subscription = subscriptionListener.getKey();
                Set<ElectricityPriceListener> listeners = subscriptionListener.getValue();

                if (subscription instanceof SpotPriceSubscription spotPriceSubscription) {
                    spotPricesSubscribed = true;
                    if (downloadSpotPrices(spotPriceSubscription)) {
                        spotPricesUpdatedListeners.addAll(listeners);
                    }
                    long numberOfFutureSpotPricesForSubscription = getCacheManager(subscription)
                            .getNumberOfFutureSpotPrices();
                    if (numberOfFutureSpotPrices == 0
                            || numberOfFutureSpotPricesForSubscription < numberOfFutureSpotPrices) {
                        numberOfFutureSpotPrices = numberOfFutureSpotPricesForSubscription;
                    }
                } else if (subscription instanceof DatahubPriceSubscription datahubPriceSubscription) {
                    downloadTariffs(datahubPriceSubscription);
                }
                updateCurrentPrices(subscription);
                publishPricesFromCache(subscription, listeners);
            }

            reschedulePriceUpdateJob();

            if (spotPricesSubscribed) {
                LocalTime now = LocalTime.now(NORD_POOL_TIMEZONE);

                if (numberOfFutureSpotPrices >= 13 || (numberOfFutureSpotPrices == 12
                        && now.isAfter(DAILY_REFRESH_TIME_CET.minusHours(1)) && now.isBefore(DAILY_REFRESH_TIME_CET))) {
                    spotPricesUpdatedListeners.forEach(listener -> listener.onDayAheadAvailable());
                    retryPolicy = RetryPolicyFactory.atFixedTime(DAILY_REFRESH_TIME_CET, NORD_POOL_TIMEZONE);
                } else {
                    logger.warn("Spot prices are not available, retry scheduled (see details in Thing properties)");
                    retryPolicy = RetryPolicyFactory.whenExpectedSpotPriceDataMissing();
                }
            } else {
                retryPolicy = RetryPolicyFactory.atFixedTime(LocalTime.MIDNIGHT, timeZoneProvider.getTimeZone());
            }
        } catch (DataServiceException e) {
            if (e.getHttpStatus() != 0) {
                listenerToSubscriptions.keySet().forEach(
                        listener -> listener.onCommunicationError(HttpStatus.getCode(e.getHttpStatus()).getMessage()));
            } else {
                listenerToSubscriptions.keySet().forEach(listener -> listener.onCommunicationError(e.getMessage()));
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

    public Map<Instant, BigDecimal> getPrices(Subscription subscription)
            throws InterruptedException, DataServiceException {
        if (subscription instanceof SpotPriceSubscription spotPriceSubscription) {
            downloadSpotPrices(spotPriceSubscription);

            return getCacheManager(subscription).getSpotPrices();
        } else if (subscription instanceof DatahubPriceSubscription datahubPriceSubscription) {
            downloadTariffs(datahubPriceSubscription);

            return getCacheManager(subscription).getTariffs(datahubPriceSubscription.getDatahubTariff());
        }
        throw new IllegalArgumentException("Subscription " + subscription + " is not supported");
    }

    private boolean downloadSpotPrices(SpotPriceSubscription subscription)
            throws InterruptedException, DataServiceException {
        CacheManager cacheManager = getCacheManager(subscription);
        if (cacheManager.areSpotPricesFullyCached()) {
            logger.debug("Cached spot prices still valid, skipping download.");
            return false;
        }
        DateQueryParameter start;
        if (cacheManager.areHistoricSpotPricesCached()) {
            start = DateQueryParameter.of(DateQueryParameterType.UTC_NOW);
        } else {
            start = DateQueryParameter.of(DateQueryParameterType.UTC_NOW,
                    Duration.ofHours(-CacheManager.NUMBER_OF_HISTORIC_HOURS));
        }
        Map<String, String> properties = new HashMap<>();
        try {
            ElspotpriceRecord[] spotPriceRecords = apiController.getSpotPrices(subscription.getPriceArea(),
                    subscription.getCurrency(), start, DateQueryParameter.EMPTY, properties);
            cacheManager.putSpotPrices(spotPriceRecords, subscription.getCurrency());
        } finally {
            subscriptionToListeners.getOrDefault(subscription, ConcurrentHashMap.newKeySet())
                    .forEach(listener -> listener.onPropertiesUpdated(properties));
        }
        return true;
    }

    private void downloadTariffs(DatahubPriceSubscription subscription)
            throws InterruptedException, DataServiceException {
        GlobalLocationNumber globalLocationNumber = subscription.getGlobalLocationNumber();
        if (globalLocationNumber.isEmpty()) {
            return;
        }
        DatahubTariff datahubTariff = subscription.getDatahubTariff();
        CacheManager cacheManager = getCacheManager(subscription);
        if (cacheManager.areTariffsValidTomorrow(datahubTariff)) {
            logger.debug("Cached tariffs of type {} still valid, skipping download.", datahubTariff);
            cacheManager.updateTariffs(datahubTariff);
        } else {
            cacheManager.putTariffs(datahubTariff, downloadPriceLists(subscription));
        }
    }

    private Collection<DatahubPricelistRecord> downloadPriceLists(DatahubPriceSubscription subscription)
            throws InterruptedException, DataServiceException {
        Map<String, String> properties = new HashMap<>();
        try {
            return apiController.getDatahubPriceLists(subscription.getGlobalLocationNumber(), ChargeType.Tariff,
                    subscription.getFilter(), properties);
        } finally {
            subscriptionToListeners.getOrDefault(subscription, ConcurrentHashMap.newKeySet())
                    .forEach(listener -> listener.onPropertiesUpdated(properties));
        }
    }

    private void publishPricesFromCache(Subscription subscription, Set<ElectricityPriceListener> listeners) {
        CacheManager cacheManager = getCacheManager(subscription);

        if (subscription instanceof SpotPriceSubscription spotPriceSubscription) {
            listeners.forEach(listener -> listener.onSpotPrices(cacheManager.getSpotPrices(),
                    spotPriceSubscription.getCurrency()));
        } else if (subscription instanceof DatahubPriceSubscription datahubPriceSubscription) {
            DatahubTariff datahubTariff = datahubPriceSubscription.getDatahubTariff();
            listeners.forEach(listener -> listener.onTariffs(datahubTariff, cacheManager.getTariffs(datahubTariff)));
        }
    }

    private void updatePricesForAllSubscriptions() {
        subscriptionToListeners.keySet().stream().forEach(this::updateCurrentPrices);

        // Clean up caches not directly related to listener subscriptions, e.g. from Thing
        // actions when having no linked channels.
        subscriptionCaches.entrySet().stream().filter(entry -> !subscriptionToListeners.containsKey(entry.getKey()))
                .forEach(entry -> entry.getValue().cleanup());

        // FIXME: Delete empty cache objects

        reschedulePriceUpdateJob();
    }

    private void updateCurrentPrices(Subscription subscription) {
        getCacheManager(subscription).cleanup();
        publishCurrentPriceFromCache(subscription,
                subscriptionToListeners.getOrDefault(subscription, ConcurrentHashMap.newKeySet()));
    }

    public @Nullable BigDecimal getCurrentPriceIfCached(Subscription subscription) {
        CacheManager cacheManager = getCacheManager(subscription);
        if (subscription instanceof SpotPriceSubscription) {
            return cacheManager.getSpotPrice();
        } else if (subscription instanceof DatahubPriceSubscription datahubPriceSubscription) {
            return cacheManager.getTariff(datahubPriceSubscription.getDatahubTariff());
        }
        throw new IllegalArgumentException("Subscription " + subscription + " is not supported");
    }

    public Map<Instant, BigDecimal> getPricesIfCached(Subscription subscription) {
        CacheManager cacheManager = getCacheManager(subscription);
        if (subscription instanceof SpotPriceSubscription) {
            return cacheManager.getSpotPrices();
        } else if (subscription instanceof DatahubPriceSubscription datahubPriceSubscription) {
            return cacheManager.getTariffs(datahubPriceSubscription.getDatahubTariff());
        }
        throw new IllegalArgumentException("Subscription " + subscription + " is not supported");
    }

    private void publishCurrentPriceFromCache(Subscription subscription, Set<ElectricityPriceListener> listeners) {
        CacheManager cacheManager = getCacheManager(subscription);

        if (subscription instanceof SpotPriceSubscription spotPriceSubscription) {
            BigDecimal spotPrice = cacheManager.getSpotPrice();
            listeners.forEach(listener -> listener.onCurrentSpotPrice(spotPrice, spotPriceSubscription.getCurrency()));
        } else if (subscription instanceof DatahubPriceSubscription datahubPriceSubscription) {
            DatahubTariff datahubTariff = datahubPriceSubscription.getDatahubTariff();
            BigDecimal tariff = cacheManager.getTariff(datahubTariff);
            listeners.forEach(listener -> listener.onCurrentTariff(datahubTariff, tariff));
        }
    }

    private void reschedulePriceUpdateJob() {
        ScheduledFuture<?> priceUpdateJob = this.priceUpdateFuture;
        if (priceUpdateJob != null) {
            // Do not interrupt ourselves.
            priceUpdateJob.cancel(false);
            this.priceUpdateFuture = null;
        }

        Instant nextUpdate = Instant.now().plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.HOURS);
        this.priceUpdateFuture = scheduler.at(this::updatePricesForAllSubscriptions, nextUpdate);
        logger.debug("Price update job rescheduled at {}", nextUpdate);
    }

    private void reschedulePriceRefreshJob(RetryStrategy retryPolicy) {
        // Preserve state of previous retry policy when configuration is the same.
        if (!retryPolicy.equals(this.retryPolicy)) {
            this.retryPolicy = retryPolicy;
        }

        ScheduledFuture<?> refreshJob = this.refreshFuture;

        long secondsUntilNextRefresh = this.retryPolicy.getDuration().getSeconds();
        Instant timeOfNextRefresh = Instant.now().plusSeconds(secondsUntilNextRefresh);
        this.refreshFuture = scheduler.at(this::refreshElectricityPrices, timeOfNextRefresh);
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
