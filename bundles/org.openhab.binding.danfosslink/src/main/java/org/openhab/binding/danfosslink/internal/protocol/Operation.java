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

import java.io.Closeable;
import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * Cancels in-progress socket connection attempts as well as established sessions.
 *
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
public final class Operation implements Closeable {
    private final List<Socket> sockets = new ArrayList<>();
    private boolean closed;
    private @Nullable IOException failure;

    public synchronized void add(Socket socket) throws IOException {
        if (closed) {
            socket.close();
            throw failure(new IOException("Operation cancelled"));
        }
        sockets.add(socket);
    }

    public synchronized void check() throws IOException {
        if (closed || Thread.currentThread().isInterrupted()) {
            throw failure(new IOException("Operation cancelled"));
        }
    }

    /** Retains the control reader's failure before closing sockets that may be in use by the foreground. */
    public void fail(IOException cause) {
        close(cause);
    }

    /** Adds the initiating failure to a secondary cancellation or socket error, without changing ordinary errors. */
    public synchronized IOException failure(IOException secondary) {
        IOException cause = failure;
        if (cause == null || cause.equals(secondary.getCause())) {
            return secondary;
        }
        var result = new IOException("MDG control connection failed", cause);
        if (!cause.equals(secondary)) {
            result.addSuppressed(secondary);
        }
        return result;
    }

    @Override
    public void close() {
        close(null);
    }

    private void close(@Nullable IOException cause) {
        List<Socket> closing;
        synchronized (this) {
            // The first close wins: errors caused by normal shutdown are not connection failures.
            if (closed) {
                return;
            }
            failure = cause;
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
