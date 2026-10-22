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
package org.openhab.binding.danfosslink.internal.handler;

import static org.openhab.binding.danfosslink.internal.DanfossLinkBindingConstants.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.danfosslink.internal.WorldState.Mode;
import org.openhab.binding.danfosslink.internal.protocol.ControllerTime;
import org.openhab.binding.danfosslink.internal.protocol.dto.ModeRequestDTO;
import org.openhab.binding.danfosslink.internal.protocol.dto.ModeState;
import org.openhab.binding.danfosslink.internal.protocol.dto.ModeType;
import org.openhab.core.library.types.DateTimeType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.types.Command;
import org.openhab.core.types.State;
import org.openhab.core.types.UnDefType;

/**
 * Validates timed Pause commands and publishes controller-confirmed state.
 *
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
final class PauseState {
    record Request(boolean cancel, @Nullable Instant until) {
        ModeRequestDTO body(@Nullable Mode current, Instant now, ControllerTime time) {
            if (current == null) {
                throw new IllegalArgumentException("Pause state is unavailable");
            }
            var body = new ModeRequestDTO(ModeType.PAUSE, ModeState.fromBoolean(!cancel));
            if (!cancel) {
                Instant requested = until;
                Instant end = (requested == null ? now.plusSeconds(3600) : requested).truncatedTo(ChronoUnit.SECONDS);
                validate(end, now);
                body.endTime = time.toControllerLocal(end, current.localZone());
            }
            return body;
        }
    }

    static Request command(String channel, Command command, Instant now) {
        if (CHANNEL_PAUSE.equals(channel) && command instanceof OnOffType onOff) {
            return new Request(onOff == OnOffType.OFF, null);
        }
        if (CHANNEL_PAUSE_UNTIL.equals(channel) && command instanceof DateTimeType date) {
            Instant end = date.getInstant().truncatedTo(ChronoUnit.SECONDS);
            validate(end, now);
            return new Request(false, end);
        }
        throw new IllegalArgumentException("Unsupported pause channel or command type");
    }

    static State state(@Nullable Mode pause, String channel) {
        if (pause == null) {
            return UnDefType.UNDEF;
        }
        boolean active = pause.state() == ModeState.ACTIVE;
        Instant end = pause.end();
        return switch (channel) {
            case CHANNEL_PAUSE -> active ? OnOffType.ON : OnOffType.OFF;
            case CHANNEL_PAUSE_UNTIL -> active && end != null ? new DateTimeType(end) : UnDefType.UNDEF;
            default -> UnDefType.UNDEF;
        };
    }

    private static void validate(Instant end, Instant now) {
        // Dates beyond the controller's supported range are indefinite-pause sentinels.
        if (!end.isAfter(now) || end.atOffset(java.time.ZoneOffset.UTC).getYear() > 2099) {
            throw new IllegalArgumentException("Pause Until must be in the future and no later than 2099");
        }
    }
}
