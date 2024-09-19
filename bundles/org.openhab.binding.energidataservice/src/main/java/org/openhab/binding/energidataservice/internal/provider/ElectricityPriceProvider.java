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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.http.HttpStatus;
import org.openhab.binding.energidataservice.internal.ApiController;
import org.openhab.binding.energidataservice.internal.api.ChargeType;
import org.openhab.binding.energidataservice.internal.api.DateQueryParameter;
import org.openhab.binding.energidataservice.internal.api.DateQueryParameterType;
import org.openhab.binding.energidataservice.internal.api.GlobalLocationNumber;
import org.openhab.binding.energidataservice.internal.api.dto.DatahubPricelistRecord;
import org.openhab.binding.energidataservice.internal.api.dto.ElspotpriceRecord;
import org.openhab.binding.energidataservice.internal.exception.DataServiceException;
import org.openhab.binding.energidataservice.internal.provider.cache.DatahubPriceSubscriptionCache;
import org.openhab.binding.energidataservice.internal.provider.cache.ElectricityPriceSubscriptionCache;
import org.openhab.binding.energidataservice.internal.provider.cache.SpotPriceSubscriptionCache;
import org.openhab.binding.energidataservice.internal.provider.cache.SubscriptionDataCache;
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
    private final Map<Subscription, SubscriptionDataCache<?>> subscriptionDataCaches = new ConcurrentHashMap<>();

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
            subscriptionDataCaches.remove(subscription);
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

    private SpotPriceSubscriptionCache getSpotPriceSubscriptionDataCache(Subscription subscription) {
        if (!(subscription instanceof SpotPriceSubscription)) {
            throw new IllegalArgumentException("Invalid cache requested for subscription " + subscription);
        }
        SubscriptionDataCache<?> dataCache = getSubscriptionDataCache(subscription);
        if (dataCache instanceof SpotPriceSubscriptionCache spotPriceSubscriptionCache) {
            return spotPriceSubscriptionCache;
        }

        throw new IllegalArgumentException("Unexpected cache for subscription " + subscription);
    }

    private DatahubPriceSubscriptionCache getDatahubPriceSubscriptionDataCache(Subscription subscription) {
        if (!(subscription instanceof DatahubPriceSubscription)) {
            throw new IllegalArgumentException("Invalid cache requested for subscription " + subscription);
        }
        SubscriptionDataCache<?> dataCache = getSubscriptionDataCache(subscription);
        if (dataCache instanceof DatahubPriceSubscriptionCache datahubPriceSubscriptionCache) {
            return datahubPriceSubscriptionCache;
        }

        throw new IllegalArgumentException("Unexpected cache for subscription " + subscription);
    }

    private SubscriptionDataCache<?> getSubscriptionDataCache(Subscription subscription) {
        SubscriptionDataCache<?> dataCache = subscriptionDataCaches.get(subscription);
        if (dataCache != null) {
            return dataCache;
        }
        if (subscription instanceof SpotPriceSubscription spotPriceSubscription) {
            dataCache = new SpotPriceSubscriptionCache(spotPriceSubscription);
        } else if (subscription instanceof DatahubPriceSubscription datahubPriceSubscription) {
            dataCache = new DatahubPriceSubscriptionCache();
        } else {
            throw new IllegalArgumentException("No supported cache for subscription " + subscription);
        }
        subscriptionDataCaches.put(subscription, dataCache);

        return dataCache;
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
                    long numberOfFutureSpotPricesForSubscription = getSpotPriceSubscriptionDataCache(subscription)
                            .getNumberOfFuturePrices();
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

            return getSpotPriceSubscriptionDataCache(subscription).get();
        } else if (subscription instanceof DatahubPriceSubscription datahubPriceSubscription) {
            downloadTariffs(datahubPriceSubscription);

            return getDatahubPriceSubscriptionDataCache(subscription).get();
        }
        throw new IllegalArgumentException("Subscription " + subscription + " is not supported");
    }

    private boolean downloadSpotPrices(SpotPriceSubscription subscription)
            throws InterruptedException, DataServiceException {
        SpotPriceSubscriptionCache cache = getSpotPriceSubscriptionDataCache(subscription);

        if (cache.arePricesFullyCached()) {
            logger.debug("Cached spot prices still valid, skipping download.");
            return false;
        }
        DateQueryParameter start;
        if (cache.areHistoricPricesCached()) {
            start = DateQueryParameter.of(DateQueryParameterType.UTC_NOW);
        } else {
            start = DateQueryParameter.of(DateQueryParameterType.UTC_NOW,
                    Duration.ofHours(-ElectricityPriceSubscriptionCache.NUMBER_OF_HISTORIC_HOURS));
        }
        Map<String, String> properties = new HashMap<>();
        try {
            ElspotpriceRecord[] spotPriceRecords = apiController.getSpotPrices(subscription.getPriceArea(),
                    subscription.getCurrency(), start, DateQueryParameter.EMPTY, properties);
            cache.put(spotPriceRecords);
        } finally {
            getListeners(subscription).forEach(listener -> listener.onPropertiesUpdated(properties));
        }
        return true;
    }

    private void downloadTariffs(DatahubPriceSubscription subscription)
            throws InterruptedException, DataServiceException {
        GlobalLocationNumber globalLocationNumber = subscription.getGlobalLocationNumber();
        if (globalLocationNumber.isEmpty()) {
            return;
        }
        DatahubPriceSubscriptionCache cache = getDatahubPriceSubscriptionDataCache(subscription);
        if (cache.areTariffsValidTomorrow()) {
            logger.debug("Cached tariffs of type {} still valid, skipping download.", subscription.getDatahubTariff());
            cache.update();
        } else {
            cache.put(downloadPriceLists(subscription));
        }
    }

    private Collection<DatahubPricelistRecord> downloadPriceLists(DatahubPriceSubscription subscription)
            throws InterruptedException, DataServiceException {
        Map<String, String> properties = new HashMap<>();
        try {
            return apiController.getDatahubPriceLists(subscription.getGlobalLocationNumber(), ChargeType.Tariff,
                    subscription.getFilter(), properties);
        } finally {
            getListeners(subscription).forEach(listener -> listener.onPropertiesUpdated(properties));
        }
    }

    private void publishPricesFromCache(Subscription subscription, Set<ElectricityPriceListener> listeners) {
        if (subscription instanceof SpotPriceSubscription spotPriceSubscription) {
            SpotPriceSubscriptionCache cache = getSpotPriceSubscriptionDataCache(subscription);
            listeners.forEach(listener -> listener.onSpotPrices(cache.get(), spotPriceSubscription.getCurrency()));
        } else if (subscription instanceof DatahubPriceSubscription datahubPriceSubscription) {
            DatahubPriceSubscriptionCache cache = getDatahubPriceSubscriptionDataCache(subscription);
            listeners.forEach(listener -> listener.onTariffs(datahubPriceSubscription.getDatahubTariff(), cache.get()));
        }
    }

    private void updatePricesForAllSubscriptions() {
        subscriptionToListeners.keySet().stream().forEach(this::updateCurrentPrices);

        // Clean up caches not directly related to listener subscriptions, e.g. from Thing
        // actions when having no linked channels.
        subscriptionDataCaches.entrySet().stream().filter(entry -> !subscriptionToListeners.containsKey(entry.getKey()))
                .forEach(entry -> entry.getValue().cleanup());

        reschedulePriceUpdateJob();
    }

    private void updateCurrentPrices(Subscription subscription) {
        getSubscriptionDataCache(subscription).cleanup();
        publishCurrentPriceFromCache(subscription, getListeners(subscription));
    }

    public @Nullable BigDecimal getCurrentPriceIfCached(Subscription subscription) {
        return getSubscriptionDataCache(subscription).get(Instant.now());
    }

    public Map<Instant, BigDecimal> getPricesIfCached(Subscription subscription) {
        return getSubscriptionDataCache(subscription).get();
    }

    private void publishCurrentPriceFromCache(Subscription subscription, Set<ElectricityPriceListener> listeners) {
        BigDecimal currentPrice = getSubscriptionDataCache(subscription).get(Instant.now());
        if (subscription instanceof SpotPriceSubscription spotPriceSubscription) {
            listeners.forEach(
                    listener -> listener.onCurrentSpotPrice(currentPrice, spotPriceSubscription.getCurrency()));
        } else if (subscription instanceof DatahubPriceSubscription datahubPriceSubscription) {
            listeners.forEach(
                    listener -> listener.onCurrentTariff(datahubPriceSubscription.getDatahubTariff(), currentPrice));
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
