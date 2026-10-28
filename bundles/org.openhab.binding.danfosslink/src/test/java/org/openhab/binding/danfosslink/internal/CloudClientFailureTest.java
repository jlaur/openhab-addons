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
package org.openhab.binding.danfosslink.internal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.EOFException;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketException;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.bouncycastle.cert.X509CertificateHolder;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.openhab.binding.danfosslink.internal.protocol.Grid;
import org.openhab.binding.danfosslink.internal.protocol.Identity;
import org.openhab.binding.danfosslink.internal.protocol.Operation;
import org.openhab.binding.danfosslink.internal.protocol.Transport;

/**
 * The control-reader failure remains visible when it closes a foreground request's socket.
 *
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
@Timeout(30)
class CloudClientFailureTest {
    @Test
    void reportsBackgroundCauseForBothSendAndReceiveFailures() throws Exception {
        for (boolean duringSend : new boolean[] { true, false }) {
            var operation = new Operation();
            var peer = mock(Transport.class);
            var grid = mock(Grid.class);
            var socket = mock(Socket.class);
            var entered = new CountDownLatch(1);
            var closed = new CountDownLatch(1);
            var cause = new EOFException("MDG control stream ended");
            var secondary = new SocketException("Socket closed");
            operation.add(socket);
            doAnswer(call -> {
                closed.countDown();
                return null;
            }).when(socket).close();
            doAnswer(call -> {
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                throw cause;
            }).when(grid).maintain();
            when(grid.call(any())).thenReturn(peer);
            org.mockito.stubbing.Answer<Object> fail = call -> {
                entered.countDown();
                assertTrue(closed.await(10, TimeUnit.SECONDS));
                throw secondary;
            };
            if (duringSend) {
                doAnswer(fail).when(peer).send(any());
            } else {
                doAnswer(fail).when(peer).receive();
            }
            try (var grids = mockStatic(Grid.class)) {
                grids.when(() -> Grid.connect(any(), any(), any())).thenReturn(grid);
                try (var client = new CloudClient(mock(Identity.class), operation);
                        var session = client.open(mock(X509CertificateHolder.class))) {
                    var failure = assertThrows(IOException.class, () -> session.world(ZoneOffset.UTC));
                    assertEquals("MDG control connection failed", failure.getMessage());
                    assertSame(cause, failure.getCause());
                    assertArrayEquals(new Throwable[] { secondary }, failure.getSuppressed());
                } finally {
                    entered.countDown();
                }
            }
        }
    }
}
