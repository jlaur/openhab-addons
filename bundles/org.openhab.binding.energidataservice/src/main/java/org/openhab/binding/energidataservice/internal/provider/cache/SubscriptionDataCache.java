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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * Generic interface for caching prices related to subscription.
 *
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
public interface SubscriptionDataCache<R> {
    void put(R records);

    Map<Instant, BigDecimal> get();

    @Nullable
    BigDecimal get(Instant time);

    void cleanup();

    long getNumberOfFuturePrices();

    boolean areHistoricPricesCached();
}
