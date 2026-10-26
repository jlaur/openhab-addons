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

import static org.openhab.binding.danfosslink.internal.ProtocolJson.GSON;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import org.bouncycastle.cert.X509CertificateHolder;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.danfosslink.internal.exception.ControllerResponseException;
import org.openhab.binding.danfosslink.internal.protocol.Grid;
import org.openhab.binding.danfosslink.internal.protocol.Identity;
import org.openhab.binding.danfosslink.internal.protocol.Operation;
import org.openhab.binding.danfosslink.internal.protocol.Transport;
import org.openhab.binding.danfosslink.internal.protocol.dto.ModeRequestDTO;
import org.openhab.core.common.ThreadPoolManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

/**
 * One short-lived cloud session; writes are never automatically replayed.
 *
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
public final class CloudClient implements AutoCloseable {
    private final Logger logger = LoggerFactory.getLogger(CloudClient.class);
    private static final ExecutorService CONTROL = ThreadPoolManager.getPool("binding-danfosslink-control");
    private final Operation operation;
    private final Grid grid;
    private int requestId;

    public CloudClient(Identity identity, Operation operation) throws IOException {
        this.operation = operation;
        try (var resource = CloudClient.class.getResourceAsStream("/certificates/mdg-ca.pem")) {
            if (resource == null) {
                throw new IOException("Missing MDG trust anchor");
            }
            grid = Grid.connect(identity, Transport.cloudTrust(resource), operation);
        }
    }

    public X509CertificateHolder pair(String pin) throws IOException {
        return grid.pair(pin);
    }

    public Session open(X509CertificateHolder controller) throws IOException {
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

        public WorldState world(ZoneId zone) throws IOException {
            return WorldState.parse(object(request("GET", "/api/world", new JsonObject()), "world content"), zone);
        }

        public void apply(WorldState.Patch patch) throws IOException {
            request("PATCH", patch.uri(), patch.body());
        }

        public void home(ModeRequestDTO body) throws IOException {
            request("PUT", "/api/modes/athome", modeContent(body));
        }

        public void pause(ModeRequestDTO body) throws IOException {
            request("PUT", "/api/modes/pause", modeContent(body));
        }

        public void vacation(ModeRequestDTO body) throws IOException {
            request("PUT", "/api/modes/vacation", modeContent(body));
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
            logger.trace("Controller request {}: {} {}", id, method, uri);
            if (!"GET".equals(method)) {
                logger.trace("Controller request {} body: {}", id, content);
            }
            peer.send(envelope.toString().getBytes(StandardCharsets.UTF_8));
            return response(peer.receive(), id);
        }

        @Override
        public void close() {
            operation.close();
            maintenance.cancel(true);
        }
    }

    static JsonObject modeContent(ModeRequestDTO body) {
        return GSON.toJsonTree(body).getAsJsonObject();
    }

    static String errorDetails(JsonObject response) {
        JsonObject details = new JsonObject();
        JsonElement description = response.get("statusDescription");
        if (description != null && description.isJsonPrimitive()) {
            details.add("statusDescription", description);
        }
        JsonElement content = response.get("content");
        if (content != null && content.isJsonObject()) {
            JsonObject object = content.getAsJsonObject();
            for (String name : new String[] { "error", "errorCode", "message", "detail", "title" }) {
                JsonElement value = object.get(name);
                if (value != null && value.isJsonPrimitive()) {
                    details.add(name, value);
                }
            }
            JsonElement messages = object.get("errorMessages");
            if (messages != null && messages.isJsonArray()) {
                JsonArray errors = new JsonArray();
                for (JsonElement message : messages.getAsJsonArray()) {
                    if (errors.size() == 8) {
                        break;
                    }
                    if (message.isJsonPrimitive() && message.getAsJsonPrimitive().isString()) {
                        errors.add(message);
                    }
                }
                details.add("errorMessages", errors);
            }
        } else if (content != null && content.isJsonPrimitive()) {
            details.add("content", content);
        }
        // JSON encoding escapes line breaks; limit text-only error pages without logging whole world objects.
        String text = details.toString();
        return text.length() > 2048 ? text.substring(0, 2048) + " [truncated]" : text;
    }

    static JsonElement response(byte[] bytes, int id) throws IOException {
        Logger logger = LoggerFactory.getLogger(CloudClient.class);
        try {
            String json = new String(bytes, StandardCharsets.UTF_8);
            logger.trace("Controller response {} payload: {}", id, json);
            // The controller's cc_json encoder emits an omitted value on empty write responses.
            // This exact firmware defect also requires normalization in the official app.
            json = json.replace("\"content\":}}", "\"content\":null}}");
            JsonObject envelope = object(JsonParser.parseString(json), "envelope");
            int responseId = integer(envelope.get("id"), "ID");
            if (responseId != id) {
                logger.debug("Controller response ID {} does not match request {}", responseId, id);
                throw new IOException("Mismatched controller response ID");
            }
            JsonObject data = object(envelope.get("data"), "data");
            JsonElement type = data.get("type");
            if (type == null || !type.isJsonPrimitive() || !type.getAsJsonPrimitive().isString()
                    || !"JsonWebResponse".equals(type.getAsString())) {
                throw new IOException("Missing or unexpected controller response type");
            }
            JsonObject response = object(data.get("data"), "response data");
            int status = integer(response.get("status"), "status");
            logger.trace("Controller response {}: HTTP {}", id, status);
            if (status < 200 || status >= 300) {
                logger.debug("Controller rejected request {} with HTTP {}", id, status);
                if (logger.isTraceEnabled()) {
                    logger.trace("Controller rejection {} details: {}", id, errorDetails(response));
                }
                throw new ControllerResponseException(status);
            }
            JsonElement content = response.get("content");
            if (content == null) {
                throw new IOException("Missing controller response content");
            }
            // Empty write acknowledgements use JSON null; world() requires an object.
            return content;
        } catch (JsonParseException e) {
            logger.debug("Cannot parse controller response for request {} ({}; {} bytes)", id,
                    e.getClass().getSimpleName(), bytes.length);
            throw new IOException("Malformed controller response", e);
        }
    }

    private static JsonObject object(@Nullable JsonElement value, String field) throws IOException {
        if (value == null || !value.isJsonObject()) {
            throw new IOException("Missing or invalid controller response " + field);
        }
        return value.getAsJsonObject();
    }

    private static int integer(@Nullable JsonElement value, String field) throws IOException {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IOException("Missing or invalid controller response " + field);
        }
        try {
            return value.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw new IOException("Invalid controller response " + field, e);
        }
    }
}
