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
import java.net.Socket;
import java.net.SocketException;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;

/**
 * Failure attribution and normal cancellation remain distinct across shutdown races.
 *
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
class OperationTest {
    @Test
    void originalFailureIsAvailableBeforeSocketClosureAndSurvivesCleanup() throws Exception {
        var operation = new Operation();
        var socket = mock(Socket.class);
        var cause = new EOFException("Control stream ended");
        doAnswer(call -> {
            assertSame(cause, assertThrows(IOException.class, operation::check).getCause());
            return null;
        }).when(socket).close();
        operation.add(socket);
        operation.fail(cause);
        operation.close();
        operation.fail(new IOException("Later failure"));
        var secondary = new SocketException("Socket closed");
        var result = operation.failure(secondary);
        assertSame(cause, result.getCause());
        assertArrayEquals(new Throwable[] { secondary }, result.getSuppressed());
        assertSame(result, operation.failure(result));
        verify(socket, times(1)).close();
        var lateSocket = mock(Socket.class);
        assertSame(cause, assertThrows(IOException.class, () -> operation.add(lateSocket)).getCause());
        verify(lateSocket).close();
    }

    @Test
    void normalShutdownDoesNotBecomeAConnectionFailure() {
        var operation = new Operation();
        operation.close();
        operation.fail(new SocketException("Reader interrupted by shutdown"));
        assertNull(assertThrows(IOException.class, operation::check).getCause());
        var foreground = new IOException("Ordinary error");
        assertSame(foreground, operation.failure(foreground));
        try (var active = new Operation()) {
            assertSame(foreground, active.failure(foreground));
        }
    }
}
