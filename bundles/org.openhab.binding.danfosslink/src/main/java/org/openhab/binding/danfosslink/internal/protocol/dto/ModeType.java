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
 * Controller override types.
 *
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
public enum ModeType {
    @SerializedName("AtHome")
    AT_HOME("AtHome"),
    @SerializedName("Pause")
    PAUSE("Pause"),
    @SerializedName("Vacation")
    VACATION("Vacation");

    private final String value;

    ModeType(String value) {
        this.value = value;
    }

    @Override
    public String toString() {
        return value;
    }
}
