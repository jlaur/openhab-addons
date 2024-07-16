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
import java.util.Currency;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.http.HttpStatus;
import org.openhab.binding.energidataservice.internal.ApiController;
import org.openhab.binding.energidataservice.internal.CacheManager;
import org.openhab.binding.energidataservice.internal.ElectricityPriceListener;
import org.openhab.binding.energidataservice.internal.api.DatahubTariffFilter;
import org.openhab.binding.energidataservice.internal.api.DateQueryParameter;
import org.openhab.binding.energidataservice.internal.api.DateQueryParameterType;
import org.openhab.binding.energidataservice.internal.api.GlobalLocationNumber;
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
    private final Map<ElectricityPriceListener, Subscriber> subscribers = new HashMap<>();
    private final Set<Subscription> subscriptions = new HashSet<>();

    private @Nullable ScheduledFuture<?> refreshFuture;
    private @Nullable ScheduledFuture<?> priceUpdateFuture;
    private RetryStrategy retryPolicy = RetryPolicyFactory.initial();

    private class Subscriber {
        private ElectricityPriceListener listener;
        private Set<Subscription> subscriptions = new HashSet<>();

        public Subscriber(ElectricityPriceListener listener) {
            this.listener = listener;
        }

        public Set<Subscription> getSubscriptions() {
            return subscriptions;
        }

        public boolean hasSubscription(Subscription subscription) {
            return subscriptions.contains(subscription);
        }

        public boolean hasSubscriptionOfSameType(Subscription subscription) {
            return subscriptions.stream().anyMatch(s -> s.getClass().equals(subscription.getClass()));
        }

        public boolean hasAnySubscriptions() {
            return !subscribers.isEmpty();
        }

        public boolean removeSubscription(Subscription subscription) {
            return subscriptions.remove(subscription);
        }

        public boolean addSubscription(Subscription subscription) {
            return subscriptions.add(subscription);
        }
    }

    private class Subscription {
        protected CacheManager cacheManager = new CacheManager();
    }

    private class SpotPriceSubscription extends Subscription {
        private String priceArea;
        private Currency currency;

        public SpotPriceSubscription(String priceArea, Currency currency) {
            this.priceArea = priceArea;
            this.currency = currency;
        }

        @Override
        public boolean equals(@Nullable Object o) {
            if (o == this) {
                return true;
            }
            if (!(o instanceof SpotPriceSubscription other)) {
                return false;
            }

            return this.priceArea.equals(other.priceArea) && this.currency.equals(other.currency);
        }

        @Override
        public int hashCode() {
            return Objects.hash(priceArea, currency);
        }
    }

    private class DatahubPriceSubscription extends Subscription {
        private GlobalLocationNumber globalLocationNumber;
        private DatahubTariffFilter filter;

        public DatahubPriceSubscription(GlobalLocationNumber globalLocationNumber, DatahubTariffFilter filter) {
            this.globalLocationNumber = globalLocationNumber;
            this.filter = filter;
        }

        @Override
        public boolean equals(@Nullable Object o) {
            if (o == this) {
                return true;
            }
            if (!(o instanceof DatahubPriceSubscription other)) {
                return false;
            }

            return this.globalLocationNumber.equals(other.globalLocationNumber) && this.filter.equals(other.filter);
        }

        @Override
        public int hashCode() {
            return Objects.hash(globalLocationNumber, filter);
        }
    }

    @Activate
    public ElectricityPriceProvider(final @Reference HttpClientFactory httpClientFactory,
            final @Reference TimeZoneProvider timeZoneProvider) {
        this.timeZoneProvider = timeZoneProvider;
        this.apiController = new ApiController(httpClientFactory.getCommonHttpClient(), timeZoneProvider);
    }

    public void subscribeToSpotPrices(ElectricityPriceListener listener, String priceArea, Currency currency) {
        Subscriber subscriber = subscribers.getOrDefault(listener, new Subscriber(listener));
        subscribers.put(listener, subscriber);

        SpotPriceSubscription spotPriceSubscriptionNew = new SpotPriceSubscription(priceArea, currency);
        if (subscriber.hasSubscription(spotPriceSubscriptionNew)) {
            throw new IllegalStateException("Duplicate listener registration for " + listener.getClass().getName());
        }

        if (subscriptions.add(spotPriceSubscriptionNew)) {
            logger.trace("First subscriber, start job");
            // First subscription: New cache + schedule job
            subscriber.addSubscription(spotPriceSubscriptionNew);
            refreshFuture = scheduler.schedule(this::refreshElectricityPrices, 0, TimeUnit.SECONDS);
        } else {
            logger.trace("New subscriber, existing subscription -> publish from cache");
            // Existing subscriptions: Publish from existing cache
            // triggerSpotPriceUpdate();
            for (Subscription subscription : subscriptions) {
                if (subscription.equals(spotPriceSubscriptionNew)
                        && subscription instanceof SpotPriceSubscription spotPriceSubscription) {
                    subscriber.addSubscription(spotPriceSubscription);
                    publishCurrentSpotPriceFromCache(spotPriceSubscription);
                    publishSpotPricesFromCache(spotPriceSubscription);
                    break;
                }
            }
        }
    }

    public void subscribeToDatahubPrices(ElectricityPriceListener listener, GlobalLocationNumber globalLocationNumber,
            DatahubTariffFilter filter) {
        Subscriber subscriber = subscribers.getOrDefault(listener, new Subscriber(listener));
        subscribers.put(listener, subscriber);

        DatahubPriceSubscription datahubSubscriptionNew = new DatahubPriceSubscription(globalLocationNumber, filter);
        if (subscriber.hasSubscription(datahubSubscriptionNew)) {
            throw new IllegalStateException("Duplicate listener registration for " + listener.getClass().getName());
        }
    }

    // FIXME: This will not allow multiple spot price subscriptions for same listener
    public void unsubscribeFromSpotprices(ElectricityPriceListener listener) {
        Subscriber subscriber = subscribers.get(listener);
        if (subscriber == null) {
            throw new IllegalStateException("No active listener registration for " + listener.getClass().getName());
        }

        Set<Subscription> subscriptions = subscriber.getSubscriptions();
        SpotPriceSubscription spotPriceSubscriptionToRemove = null;
        for (Subscription subscription : subscriptions) {
            // FIXME: This will not allow multiple spot price subscriptions for same listener
            // only first subscription is removed
            if (subscription instanceof SpotPriceSubscription spotPriceSubscription) {
                spotPriceSubscriptionToRemove = spotPriceSubscription;
                break;
            }
        }
        if (spotPriceSubscriptionToRemove == null) {
            throw new IllegalStateException("No active spot price subscription for " + listener.getClass().getName());
        }

        logger.trace("unsubscribeFromSpotprices -> checking {} subscribers", subscribers.size());
        boolean isLastSubscription = true;
        for (Subscriber otherSubscriber : subscribers.values()) {
            if (!otherSubscriber.equals(subscriber) && otherSubscriber.hasSubscription(spotPriceSubscriptionToRemove)) {
                isLastSubscription = false;
                break;
            }
        }
        if (isLastSubscription) {
            subscriptions.remove(spotPriceSubscriptionToRemove);

            logger.trace("Last subscription removed -> stopping refresh future");
            ScheduledFuture<?> refreshFuture = this.refreshFuture;
            if (refreshFuture != null) {
                refreshFuture.cancel(true);
                this.refreshFuture = null;
            }
        }
        subscriber.removeSubscription(spotPriceSubscriptionToRemove);
        if (!subscriber.hasAnySubscriptions()) {
            subscribers.remove(listener);
        }
        cancelPriceUpdateJobWhenNoSubscribers();
    }

    public void triggerSpotPriceUpdate() {
        for (Subscription subscription : subscriptions) {
            if (subscription instanceof SpotPriceSubscription spotPriceSubscription) {
                publishCurrentSpotPriceFromCache(spotPriceSubscription);
                publishSpotPricesFromCache(spotPriceSubscription);
            }
        }
    }

    private void refreshElectricityPrices() {
        logger.trace("refreshElectricityPrices");
        for (Subscription subscription : subscriptions) {
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
                    for (Subscriber subscriber : subscribers.values()) {
                        if (subscriber.hasSubscription(subscription)) {
                            subscriber.listener.onDayAheadAvailable();
                        }
                    }
                }
                retryPolicy = RetryPolicyFactory.atFixedTime(DAILY_REFRESH_TIME_CET, NORD_POOL_TIMEZONE);
            } else {
                logger.warn("Spot prices are not available, retry scheduled (see details in Thing properties)");
                retryPolicy = RetryPolicyFactory.whenExpectedSpotPriceDataMissing();
            }
        } catch (DataServiceException e) {
            if (e.getHttpStatus() != 0) {
                for (Subscriber subscriber : subscribers.values()) {
                    if (subscriber.hasSubscription(subscription)) {
                        subscriber.listener.onCommunicationError(HttpStatus.getCode(e.getHttpStatus()).getMessage());
                    }
                }
            } else {
                getListenersForSubscription(subscription)
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
            ElspotpriceRecord[] spotPriceRecords = apiController.getSpotPrices(subscription.priceArea,
                    subscription.currency, start, DateQueryParameter.EMPTY, properties);
            subscription.cacheManager.putSpotPrices(spotPriceRecords, subscription.currency);
        } finally {
            getListenersForSubscription(subscription).forEach(listener -> listener.onPropertiesUpdated(properties));
        }
        return true;
    }

    private void publishSpotPricesFromCache(SpotPriceSubscription subscription) {
        getListenersForSubscription(subscription).forEach(
                listener -> listener.onSpotPrices(subscription.cacheManager.getSpotPrices(), subscription.currency));
    }

    private void updateAllPrices() {
        for (Subscription subscription : subscriptions) {
            if (subscription instanceof SpotPriceSubscription spotPriceSubscription) {
                updatePrices(spotPriceSubscription);
            }
        }
        reschedulePriceUpdateJob();
    }

    private void updatePrices(SpotPriceSubscription subscription) {
        subscription.cacheManager.cleanup();
        publishCurrentSpotPriceFromCache(subscription);
    }

    private Stream<ElectricityPriceListener> getListenersForSubscription(Subscription subscription) {
        return subscribers.values().stream().filter(subscriber -> subscriber.hasSubscription(subscription))
                .map(subscriber -> subscriber.listener);
    }

    private void publishCurrentSpotPriceFromCache(SpotPriceSubscription subscription) {
        BigDecimal spotPrice = subscription.cacheManager.getSpotPrice();
        getListenersForSubscription(subscription)
                .forEach(listener -> listener.onCurrentSpotPrice(spotPrice, subscription.currency));
    }

    private void cancelPriceUpdateJobWhenNoSubscribers() {
        if (subscribers.isEmpty()) {
            logger.trace("cancelPriceUpdateJobWhenNoSubscribers -> no subscribers");
            ScheduledFuture<?> priceUpdateJob = this.priceUpdateFuture;
            if (priceUpdateJob != null) {
                priceUpdateJob.cancel(true);
                this.priceUpdateFuture = null;
            }
        }
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
        for (Subscriber subscriber : subscribers.values()) {
            subscriber.listener.onPropertiesUpdated(propertyMap);
        }

        if (refreshJob != null) {
            refreshJob.cancel(true);
        }
    }
}
