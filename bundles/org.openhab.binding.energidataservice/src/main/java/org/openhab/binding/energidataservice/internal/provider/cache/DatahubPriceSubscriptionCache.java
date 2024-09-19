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
package org.openhab.binding.energidataservice.internal.provider.cache;

import static org.openhab.binding.energidataservice.internal.EnergiDataServiceBindingConstants.*;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.energidataservice.internal.EnergiDataServiceBindingConstants;
import org.openhab.binding.energidataservice.internal.PriceListParser;
import org.openhab.binding.energidataservice.internal.api.dto.DatahubPricelistRecord;

/**
 * Datahub price (tariff) specific {@link ElectricityPriceSubscriptionCache} implementation.
 *
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
public class DatahubPriceSubscriptionCache
        extends ElectricityPriceSubscriptionCache<Collection<DatahubPricelistRecord>> {

    private final PriceListParser priceListParser = new PriceListParser();

    private Collection<DatahubPricelistRecord> datahubRecords = List.of();

    public DatahubPriceSubscriptionCache() {
        this(Clock.systemDefaultZone());
    }

    public DatahubPriceSubscriptionCache(Clock clock) {
        super(clock);
    }

    /**
     * Replace current "raw"/unprocessed tariff records in cache.
     * Map of hourly tariffs will be updated automatically.
     * 
     * @param records The records as received from Energi Data Service.
     */
    @Override
    public void put(Collection<DatahubPricelistRecord> records) {
        LocalDateTime localHourStart = LocalDateTime.now(clock.withZone(DATAHUB_TIMEZONE))
                .minus(NUMBER_OF_HISTORIC_HOURS, ChronoUnit.HOURS).truncatedTo(ChronoUnit.HOURS);

        datahubRecords.clear();
        datahubRecords.addAll(records.stream().filter(r -> !r.validTo().isBefore(localHourStart)).toList());
        update();
    }

    /**
     * Update map of hourly tariffs from internal cache.
     */
    public void update() {
        priceMap.putAll(priceListParser.toHourly(datahubRecords));
        cleanup();
    }

    /**
     * Check if we have "raw" tariff records cached which are valid tomorrow.
     * 
     * @return true if tariff records for tomorrow are cached
     */
    public boolean areTariffsValidTomorrow() {
        LocalDateTime localHourStart = LocalDateTime.now(EnergiDataServiceBindingConstants.DATAHUB_TIMEZONE)
                .truncatedTo(ChronoUnit.HOURS);
        LocalDateTime localMidnight = localHourStart.plusDays(1).truncatedTo(ChronoUnit.DAYS);

        return datahubRecords.stream().anyMatch(r -> r.validTo().isAfter(localMidnight));
    }
}
