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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import org.bouncycastle.cert.X509CertificateHolder;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.common.ThreadPoolManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * One short-lived cloud session; writes are never automatically replayed.
 *
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
public final class CloudClient implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(CloudClient.class);
    private static final ExecutorService CONTROL = ThreadPoolManager.getPool("binding-danfosslink-control");
    private final Operation operation;
    private final Grid grid;
    private int requestId;

    public CloudClient(Identity identity, Operation operation) throws Exception {
        this.operation = operation;
        try (var resource = CloudClient.class.getResourceAsStream("/certificates/mdg-ca.pem")) {
            if (resource == null) {
                throw new IOException("Missing MDG trust anchor");
            }
            grid = Grid.connect(identity, Transport.cloudTrust(resource), operation);
        }
    }

    public X509CertificateHolder pair(String pin) throws Exception {
        return grid.pair(pin);
    }

    public Session open(X509CertificateHolder controller) throws Exception {
        Transport peer = grid.call(controller);
        Future<?> maintenance = CONTROL.submit(() -> {
            try {
                grid.maintain();
            } catch (IOException e) {
                operation.close();
            }
        });
        return new Session(peer, maintenance);
    }

    @Override
    public void close() {
        operation.close();
    }

    public final class Session implements AutoCloseable {
        private final Transport peer;
        private final Future<?> maintenance;

        public Session(Transport peer, Future<?> maintenance) {
            this.peer = peer;
            this.maintenance = maintenance;
        }

        public WorldState world() throws IOException {
            return WorldState.parse(request("GET", "/api/world", new JsonObject()).getAsJsonObject());
        }

        public void apply(WorldState.Patch patch) throws IOException {
            request("PATCH", patch.uri(), patch.body());
        }

        private JsonElement request(String method, String uri, JsonObject content) throws IOException {
            operation.check();
            int id = ++requestId;
            JsonObject request = new JsonObject();
            request.addProperty("method", method);
            request.addProperty("uri", uri);
            var headers = new com.google.gson.JsonArray();
            headers.add("Accept: application/json; charset=utf-8");
            request.add("headers", headers);
            if (!"GET".equals(method)) {
                request.add("content", content);
            }
            JsonObject data = new JsonObject();
            data.addProperty("type", "JsonWebRequest");
            data.add("data", request);
            JsonObject envelope = new JsonObject();
            envelope.addProperty("id", id);
            envelope.add("data", data);
            LOGGER.trace("Controller request {}: {} {}", id, method, uri);
            if ("PATCH".equals(method)) {
                LOGGER.trace("Controller request {} patch: {}", id, content);
            }
            peer.send(envelope.toString().getBytes(StandardCharsets.UTF_8));
            LOGGER.trace("Controller request {} sent; waiting for response", id);
            return response(peer.receive(), id);
        }

        @Override
        public void close() {
            operation.close();
            maintenance.cancel(true);
        }
    }

    public static final class ControllerResponseException extends IOException {
        private static final long serialVersionUID = 1L;

        private final int status;

        public ControllerResponseException(int status) {
            super("Controller returned HTTP status " + status);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    static JsonElement response(byte[] bytes, int id) throws IOException {
        LOGGER.trace("Controller response for request {}: {} bytes", id, bytes.length);
        try {
            String json = new String(bytes, StandardCharsets.UTF_8);
            // The controller's cc_json encoder emits an omitted value on empty write responses.
            // This exact firmware defect also requires normalization in the official app.
            json = json.replace("\"content\":}}", "\"content\":null}}");
            JsonObject envelope = JsonParser.parseString(json).getAsJsonObject();
            if (envelope.get("id").getAsInt() != id) {
                LOGGER.debug("Controller response ID {} does not match request {}", envelope.get("id").getAsInt(), id);
                throw new IOException("Mismatched controller response ID");
            }
            JsonObject data = envelope.getAsJsonObject("data");
            if (!"JsonWebResponse".equals(data.get("type").getAsString())) {
                LOGGER.debug("Controller request {} received an unexpected response type", id);
                throw new IOException("Unexpected controller response type");
            }
            JsonObject response = data.getAsJsonObject("data");
            int status = response.get("status").getAsInt();
            LOGGER.trace("Controller response {}: HTTP {}", id, status);
            if (status < 200 || status >= 300) {
                LOGGER.debug("Controller rejected request {} with HTTP {}", id, status);
                throw new ControllerResponseException(status);
            }
            return Objects.requireNonNull(response.get("content"));
        } catch (RuntimeException e) {
            LOGGER.debug("Cannot parse controller response for request {} ({}; {} bytes)", id,
                    e.getClass().getSimpleName(), bytes.length);
            throw new IOException("Malformed controller response", e);
        }
    }
}
