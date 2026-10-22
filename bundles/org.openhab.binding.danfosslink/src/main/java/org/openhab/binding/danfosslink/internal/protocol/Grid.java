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
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.bouncycastle.cert.X509CertificateHolder;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Outbound MDG control and relay connections. Each instance performs one
 * bounded operation.
 *
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
public final class Grid implements Closeable {
    private static final List<String> HOSTS = List.of("5.179.92.180", "5.179.92.182", "77.66.11.90", "77.66.11.92");
    private final Logger logger = LoggerFactory.getLogger(Grid.class);
    private final Transport control;
    private final Identity identity;
    private final Operation operation;
    private int requestId;

    private Grid(Transport control, Identity identity, Operation operation) {
        this.control = control;
        this.identity = identity;
        this.operation = operation;
    }

    public static Grid connect(Identity identity, Transport.Verifier trust, Operation operation) throws IOException {
        IOException failure = new IOException("No MDG control server was available");
        for (String host : HOSTS) {
            operation.check();
            try {
                Transport control = Transport.connect(host, 443, operation);
                try {
                    control.startTls(identity, false, trust);
                    var grid = new Grid(control, identity, operation);
                    grid.handshake();
                    return grid;
                } catch (IOException | RuntimeException e) {
                    control.close();
                    throw e;
                }
            } catch (IOException e) {
                failure.addSuppressed(e);
            }
        }
        throw failure;
    }

    private void handshake() throws IOException {
        var hello = Wire.message(1).fixed32(1, 0xf09d8ca8).number(2, 1).number(3, 0);
        for (var entry : Map.of("app.package.name", "com.danfoss.linkapp", "app.display.name", "release",
                "app.version.name", "Android-1.3.2", "app.version.code", "11415").entrySet()) {
            hello.bytes(4, Wire.fields().text(1, entry.getKey()).text(2, entry.getValue()).build());
        }
        control.send(hello.build());
        var reply = next();
        if (reply.type() != 1 || reply.fields().number(1) != 0xf09d8ca8L || reply.fields().number(2) != 1
                || reply.fields().number(3) != 0) {
            throw new IOException("Unexpected MDG handshake");
        }
    }

    public Transport call(X509CertificateHolder controller) throws Exception {
        String peerId = Identity.peerId(controller);
        request(20, 21, Wire.fields().text(2, peerId));
        return route(request(10, 11, Wire.fields().text(2, peerId).text(3, "cc_json")), Transport.pinned(controller));
    }

    public X509CertificateHolder pair(String code) throws Exception {
        if (!code.matches("[0-9]{9}")) {
            throw new IllegalArgumentException("The cloud pairing code must contain nine digits");
        }
        var reply = request(32, 33, Wire.fields().text(2, code.substring(0, 4)));
        // The initial certificate is provisional: PACE authenticates it by binding both
        // public keys to the OTP.
        // It is returned for persistence only after the proof and contact exchange
        // succeed.
        try (var peer = route(reply, chain -> {
        })) {
            var maintenance = org.openhab.core.common.ThreadPoolManager.getPool("binding-danfosslink-control")
                    .submit(() -> {
                        try {
                            maintain();
                        } catch (IOException e) {
                            operation.close();
                        }
                    });
            try {
                Pairing.run(new Pairing.Exchange() {
                    @Override
                    public void send(byte[] packet) throws IOException {
                        peer.send(packet);
                    }

                    @Override
                    public byte[] receive() throws IOException {
                        return peer.receive();
                    }
                }, code.substring(4).getBytes(StandardCharsets.US_ASCII), Identity.publicKeyPem(peer.peerCertificate()),
                        Identity.publicKeyPem(identity.certificate));
                return peer.peerCertificate();
            } finally {
                operation.close();
                maintenance.cancel(true);
            }
        }
    }

    private Transport route(Wire.Fields reply, Transport.Verifier verifier) throws IOException {
        long result = reply.number(2);
        if (result != 0) {
            throw new IOException("MDG call failed with result " + result + (result == 1 ? " (peer unavailable)" : ""));
        }
        var routing = Wire.decodeFields(reply.bytes(3));
        var endpoints = routing.repeatedBytes(4);
        if (endpoints.isEmpty()) {
            throw new IOException("MDG returned no relay");
        }
        // Do not automatically replay a routing token against multiple relays: it may
        // be single-use.
        var endpoint = Wire.decodeFields(endpoints.getFirst());
        String host = endpoint.text(1);
        long port = endpoint.number(2);
        if (host.isBlank() || port < 1 || port > 65535) {
            throw new IOException("Invalid relay endpoint");
        }
        logger.trace("MDG connecting to relay {}:{}", host, port);
        Transport peer = Transport.connect(host, (int) port, operation);
        String stage = "relay introduction";
        try {
            peer.send(Wire.message(0).fixed32(1, 0xf09d8c95).number(2, 1).number(3, 0).bytes(4, routing.bytes(5))
                    .text(5, "tlsv1_2").build());
            stage = "waiting for relay to connect controller";
            for (int count = 0; count < 10; count++) {
                var message = Wire.decode(peer.receive());
                if (message.type() == 1) {
                    continue;
                }
                if (message.type() == 3) {
                    logger.debug("MDG relay reported error {}", message.fields().number(1));
                    throw new IOException("MDG relay error " + message.fields().number(1));
                }
                if (message.type() != 2) {
                    throw new IOException("Unexpected relay response");
                }
                if (message.fields().values().containsKey(1) && !"tlsv1_2".equals(message.fields().text(1))) {
                    throw new IOException("Unexpected relay protocol");
                }
                stage = "controller TLS handshake";
                peer.startTls(identity, true, verifier);
                logger.trace("Controller TLS handshake completed");
                return peer;
            }
            throw new IOException("Relay did not connect the controller");
        } catch (IOException | RuntimeException e) {
            logger.debug("Controller session failed during {} ({})", stage, e.getClass().getSimpleName());
            peer.close();
            throw e;
        }
    }

    private Wire.Fields request(int type, int responseType, Wire.Builder fields) throws IOException {
        int id = ++requestId;
        control.send(Wire.message(type).number(1, id).raw(fields.build()).build());
        var reply = next();
        if (reply.type() != responseType || reply.fields().number(1) != id) {
            throw new IOException("Unexpected MDG response");
        }
        return reply.fields();
    }

    private Wire.Message next() throws IOException {
        for (int count = 0; count < 100; count++) {
            var message = Wire.decode(control.receive());
            switch (message.type()) {
                case 2 -> {
                    logger.debug("MDG ended the control session, reason {}", message.fields().number(1));
                    throw new IOException("MDG ended the session, reason " + message.fields().number(1));
                }
                case 3 -> {
                    logger.debug("MDG requested reconnection");
                    throw new IOException("MDG requested reconnection; retry this operation");
                }
                case 4 -> control.send(Wire.message(5).number(1, message.fields().number(1)).build());
                case 12 -> control.send(Wire.message(13).number(1, message.fields().number(1)).number(2, 0).build());
                default -> {
                    return message;
                }
            }
        }
        throw new IOException("Too many unsolicited MDG messages");
    }

    public void maintain() throws IOException {
        while (true) {
            operation.check();
            next();
        }
    }

    @Override
    public void close() throws IOException {
        control.close();
    }
}
