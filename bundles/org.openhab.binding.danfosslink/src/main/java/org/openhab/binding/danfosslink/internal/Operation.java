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

import java.io.Closeable;
import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Cancels in-progress socket connection attempts as well as established sessions.
 *
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
public final class Operation implements Closeable {
    private final List<Socket> sockets = new ArrayList<>();
    private boolean closed;

    synchronized void add(Socket socket) throws IOException {
        if (closed) {
            socket.close();
            throw new IOException("Operation cancelled");
        }
        sockets.add(socket);
    }

    public synchronized void check() throws IOException {
        if (closed || Thread.currentThread().isInterrupted()) {
            throw new IOException("Operation cancelled");
        }
    }

    @Override
    public void close() {
        List<Socket> closing;
        synchronized (this) {
            closed = true;
            closing = List.copyOf(sockets);
            sockets.clear();
        }
        for (Socket socket : closing) {
            try {
                socket.close();
            } catch (IOException e) {
                // Cancellation is best-effort; the protocol's read deadline also bounds shutdown.
            }
        }
    }
}
