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
package org.openhab.binding.danfosslink.internal.protocol.dto;

import org.eclipse.jdt.annotation.NonNullByDefault;

import com.google.gson.annotations.SerializedName;

/**
 * Controller override states.
 *
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
public enum ModeState {
    @SerializedName("Inactive")
    INACTIVE("Inactive"),
    @SerializedName("Planned")
    PLANNED("Planned"),
    @SerializedName("Active")
    ACTIVE("Active");

    private final String value;

    ModeState(String value) {
        this.value = value;
    }

    public static ModeState fromBoolean(boolean active) {
        return active ? ACTIVE : INACTIVE;
    }

    @Override
    public String toString() {
        return value;
    }
}
