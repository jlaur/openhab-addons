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
import org.openhab.core.library.types.StringType;
import org.openhab.core.types.Command;
import org.openhab.core.types.State;
import org.openhab.core.types.UnDefType;

/**
 * Vacation staging and validation, guarded by the controller handler's lifecycle lock.
 *
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
final class VacationState {
    private @Nullable Mode reported;
    private @Nullable Instant stagedStart;
    private @Nullable Instant stagedEnd;

    record Request(String channel, Command command, @Nullable Instant start, @Nullable Instant end) {
        ModeRequestDTO body(@Nullable Mode current, Instant now, ControllerTime time) {
            if (current == null) {
                throw new IllegalArgumentException("Vacation state is unavailable");
            }
            Instant desiredStart = start;
            Instant desiredEnd = end;
            if (CHANNEL_VACATION_START.equals(channel) || CHANNEL_VACATION_END.equals(channel)) {
                if (current.state() != ModeState.PLANNED) {
                    throw new IllegalArgumentException("Vacation is no longer Planned; date change rejected");
                }
                Instant value = ((DateTimeType) command).getInstant();
                desiredStart = CHANNEL_VACATION_START.equals(channel) ? value : current.start();
                desiredEnd = CHANNEL_VACATION_END.equals(channel) ? value : current.end();
                validate(desiredStart, desiredEnd, now, false);
            } else if (command == OnOffType.ON) {
                if (current.state() != ModeState.INACTIVE) {
                    throw new IllegalArgumentException("Vacation is no longer Inactive; activation rejected");
                }
                validate(desiredStart, desiredEnd, now, true);
            }
            var body = new ModeRequestDTO(ModeType.VACATION,
                    command == OnOffType.OFF ? ModeState.INACTIVE : ModeState.ACTIVE);
            if (command != OnOffType.OFF && desiredStart != null && desiredEnd != null) {
                body.startTime = time.toControllerLocal(desiredStart, current.localZone());
                body.endTime = time.toControllerLocal(desiredEnd, current.localZone());
            }
            return body;
        }
    }

    void report(@Nullable Mode vacation) {
        Mode previous = reported;
        if (vacation != null && (vacation.state() != ModeState.INACTIVE
                || (previous != null && previous.state() != ModeState.INACTIVE))) {
            stagedStart = null;
            stagedEnd = null;
        }
        reported = vacation;
    }

    @Nullable
    Request command(String channel, Command command, Instant now) {
        Mode current = reported;
        if (current == null) {
            throw new IllegalArgumentException("Vacation state is unavailable");
        }
        if (CHANNEL_VACATION.equals(channel) && command instanceof OnOffType) {
            if (command == OnOffType.OFF) {
                stagedStart = null;
                stagedEnd = null;
                return new Request(channel, command, null, null);
            }
            if (current.state() != ModeState.INACTIVE) {
                return null;
            }
            validate(stagedStart, stagedEnd, now, true);
            return new Request(channel, command, stagedStart, stagedEnd);
        }
        if ((CHANNEL_VACATION_START.equals(channel) || CHANNEL_VACATION_END.equals(channel))
                && command instanceof DateTimeType date) {
            if (current.state() == ModeState.INACTIVE) {
                if (CHANNEL_VACATION_START.equals(channel)) {
                    stagedStart = date.getInstant();
                } else {
                    stagedEnd = date.getInstant();
                }
                return null;
            }
            if (current.state() == ModeState.PLANNED) {
                // Validate against a fresh snapshot in the worker, after earlier queued date edits.
                return new Request(channel, command, null, null);
            }
            throw new IllegalArgumentException("Date edits while Vacation is Active are not supported");
        }
        throw new IllegalArgumentException("Unsupported vacation channel or command type");
    }

    State state(String channel) {
        Mode current = reported;
        if (current == null) {
            return UnDefType.UNDEF;
        }
        boolean inactive = current.state() == ModeState.INACTIVE;
        return switch (channel) {
            case CHANNEL_VACATION -> inactive ? OnOffType.OFF : OnOffType.ON;
            case CHANNEL_VACATION_STATE -> new StringType(current.state().toString());
            case CHANNEL_VACATION_START -> date(inactive ? stagedStart : current.start());
            case CHANNEL_VACATION_END -> date(inactive ? stagedEnd : current.end());
            default -> UnDefType.UNDEF;
        };
    }

    private static State date(@Nullable Instant value) {
        return value == null ? UnDefType.UNDEF : new DateTimeType(value);
    }

    private static void validate(@Nullable Instant start, @Nullable Instant end, Instant now, boolean indefinite) {
        if (indefinite && start == null && end == null) {
            return;
        }
        if (start == null || end == null || !start.truncatedTo(ChronoUnit.SECONDS).isAfter(now)
                || !end.truncatedTo(ChronoUnit.SECONDS).isAfter(start.truncatedTo(ChronoUnit.SECONDS))) {
            throw new IllegalArgumentException("Vacation requires both dates in the future, with end after start");
        }
    }
}
