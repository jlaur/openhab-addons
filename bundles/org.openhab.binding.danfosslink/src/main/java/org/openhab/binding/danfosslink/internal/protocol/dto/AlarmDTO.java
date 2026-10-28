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

import java.math.BigDecimal;

import org.eclipse.jdt.annotation.Nullable;

import com.google.gson.JsonElement;

/**
 * Alarm fields identified in the app protocol. Unverified field representations are retained as JSON.
 *
 * @author Jacob Laursen - Initial contribution
 */
public class AlarmDTO {
    public @Nullable BigDecimal id;
    public @Nullable Boolean isRead;
    public @Nullable JsonElement createdDate;
    public @Nullable JsonElement level;
    public @Nullable JsonElement room;
    public @Nullable JsonElement node;
    public @Nullable JsonElement descriptionTextId;
    public @Nullable JsonElement titleTextId;
}
