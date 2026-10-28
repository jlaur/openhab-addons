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
package org.openhab.binding.danfosslink.internal.protocol;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.EOFException;
import java.io.IOException;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.binding.danfosslink.internal.exception.CloudServerException;
import org.openhab.binding.danfosslink.internal.exception.ControllerConnectionTimeoutException;
import org.openhab.binding.danfosslink.internal.exception.ControllerUnavailableException;
import org.openhab.binding.danfosslink.internal.exception.RelayConnectionClosedException;

/**
 * Verifies cloud failure classification from MDG responses.
 *
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
class GridTest {
    @Test
    void callResultsDistinguishUnavailableControllerFromServerFailure() throws Exception {
        assertInstanceOf(ControllerUnavailableException.class, failure(1, 0, false));
        assertInstanceOf(CloudServerException.class, failure(2, 0, false));
        assertEquals(IOException.class, failure(99, -1, false).getClass());
    }

    @Test
    void relayErrorsDistinguishTimeoutFromServerFailure() throws Exception {
        assertInstanceOf(ControllerConnectionTimeoutException.class, failure(0, 4, false));
        assertInstanceOf(CloudServerException.class, failure(0, 1, false));
        for (var entry : java.util.Map
                .of(0, "NO_ERROR", 2, "SYNTAX_ERROR", 3, "INVALID_TOKEN", 5, "UNSUPPORTED_VERSION", 99, "UNKNOWN")
                .entrySet()) {
            IOException failure = failure(0, entry.getKey(), false);
            assertEquals(IOException.class, failure.getClass());
            assertEquals("MDG relay error " + entry.getValue() + " (" + entry.getKey() + ")", failure.getMessage());
        }
    }

    @Test
    void unknownCallCodesRetainTheirNumericValueWithoutTruncation() throws Exception {
        for (long code : new long[] { 99, 4294967297L }) {
            var failure = failure(code, -1, false);
            assertEquals(IOException.class, failure.getClass());
            assertEquals("MDG call failed with result UNKNOWN (" + code + ")", failure.getMessage());
        }
        assertEquals(RelayError.UNKNOWN, RelayError.fromCode(4294967297L));
    }

    @Test
    void onlyEofWhileWaitingForControllerIsClassifiedAsRelayClosure() throws Exception {
        var failure = failure(0, -1, false);
        assertInstanceOf(RelayConnectionClosedException.class, failure);
        assertInstanceOf(EOFException.class, failure.getCause());
        assertEquals(1, failure.getSuppressed().length);
        assertEquals(EOFException.class, failure(0, 0, true).getClass());
    }

    @Test
    void sessionTerminationReportsKnownAndUnknownReasons() throws Exception {
        var identity = Identity.generate();
        var control = mock(Transport.class);
        try (var transports = mockStatic(Transport.class); var operation = new Operation()) {
            transports.when(() -> Transport.connect(anyString(), eq(443), same(operation))).thenReturn(control);
            for (var entry : java.util.Map.of(0L, "NO_ERROR", 1L, "INTERNAL_ERROR", 2L, "SYNTAX_ERROR", 3L, "DENIED",
                    4L, "TEMPORARILY_DENIED", 5L, "PROTOCOL_ERROR", 6L, "TIMEOUT", 7L, "UNSUPPORTED_VERSION", 99L,
                    "UNKNOWN", 4294967297L, "UNKNOWN").entrySet()) {
                when(control.receive()).thenReturn(
                        Wire.message(1).fixed32(1, 0xf09d8ca8).number(2, 1).number(3, 0).build(),
                        Wire.message(2).number(1, entry.getKey()).build());
                try (var grid = Grid.connect(identity, chain -> {
                }, operation)) {
                    var failure = assertThrows(IOException.class, grid::maintain);
                    assertEquals("MDG ended the session, reason " + entry.getValue() + " (" + entry.getKey() + ")",
                            failure.getMessage());
                }
            }
        }
    }

    private IOException failure(long result, int relayError, boolean tls) throws Exception {
        var identity = Identity.generate();
        var control = mock(Transport.class);
        var peer = mock(Transport.class);
        var endpoint = Wire.fields().text(1, "relay.test").number(2, 443).build();
        var routing = Wire.fields().bytes(4, endpoint).bytes(5, new byte[] { 1 }).build();
        when(control.receive()).thenReturn(Wire.message(1).fixed32(1, 0xf09d8ca8).number(2, 1).number(3, 0).build(),
                Wire.message(21).number(1, 1).build(),
                Wire.message(11).number(1, 2).number(2, result).bytes(3, routing).build());
        if (tls) {
            when(peer.receive()).thenReturn(Wire.message(2).text(1, "tlsv1_2").build());
            doThrow(new EOFException()).when(peer).startTls(any(), eq(true), any());
        } else if (relayError == -1) {
            when(peer.receive()).thenThrow(new EOFException());
            doThrow(new IOException("Close failed")).when(peer).close();
        } else {
            when(peer.receive()).thenReturn(Wire.message(1).build(), Wire.message(3).number(1, relayError).build());
        }
        try (var transports = mockStatic(Transport.class); var operation = new Operation()) {
            transports.<Transport.Verifier> when(() -> Transport.pinned(identity.certificate)).thenReturn(chain -> {
            });
            transports.when(() -> Transport.connect(anyString(), eq(443), same(operation)))
                    .thenAnswer(call -> "relay.test".equals(call.getArgument(0)) ? peer : control);
            try (var grid = Grid.connect(identity, chain -> {
            }, operation)) {
                var failure = assertThrows(IOException.class, () -> grid.call(identity.certificate));
                if (result == 0) {
                    verify(peer).close();
                } else {
                    verifyNoInteractions(peer);
                }
                return failure;
            }
        }
    }
}
