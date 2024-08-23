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

import java.util.Objects;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.energidataservice.internal.api.DatahubTariffFilter;
import org.openhab.binding.energidataservice.internal.api.GlobalLocationNumber;

/**
 * Class for datahub price subscription.
 *
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
public class DatahubPriceSubscription extends Subscription {
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
