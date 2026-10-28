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
import org.openhab.binding.danfosslink.internal.protocol.Alarms;
import org.openhab.binding.danfosslink.internal.protocol.Grid;
import org.openhab.binding.danfosslink.internal.protocol.HeatingSchedule;
import org.openhab.binding.danfosslink.internal.protocol.Identity;
import org.openhab.binding.danfosslink.internal.protocol.Operation;
import org.openhab.binding.danfosslink.internal.protocol.Transport;
import org.openhab.binding.danfosslink.internal.protocol.dto.ErrorResponseDTO;
import org.openhab.binding.danfosslink.internal.protocol.dto.JsonWebResponseDTO;
import org.openhab.binding.danfosslink.internal.protocol.dto.ModeRequestDTO;
import org.openhab.binding.danfosslink.internal.protocol.dto.ResponseEnvelopeDTO;
import org.openhab.binding.danfosslink.internal.protocol.dto.RoomDetailsDTO;
import org.openhab.binding.danfosslink.internal.protocol.dto.WeeklyScheduleDTO;
import org.openhab.core.common.ThreadPoolManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

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
                operation.fail(e);
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

        /**
         * Reads the detailed room response, including its weekly schedule when supplied by the controller.
         */
        public RoomDetailsDTO room(int roomId) throws IOException {
            if (roomId < 0) {
                throw new IllegalArgumentException("Negative room ID");
            }
            JsonObject content = object(request("GET", "/api/rooms/" + roomId, new JsonObject()), "room content");
            try {
                RoomDetailsDTO room = GSON.fromJson(content, RoomDetailsDTO.class);
                var id = room == null ? null : room.id;
                if (room == null || id == null || id.compareTo(java.math.BigDecimal.valueOf(roomId)) != 0) {
                    throw new IOException("Missing or mismatched room ID");
                }
                return room;
            } catch (JsonParseException e) {
                throw new IOException("Malformed room response", e);
            }
        }

        /**
         * Reads the dedicated alarm collection (API v3), whose scope may differ from unresolved world alarms.
         */
        public Alarms alarms() throws IOException {
            return Alarms.parse(request("GET", "/api/alarms", new JsonObject()));
        }

        public void roomSchedule(int roomId, WeeklyScheduleDTO schedule) throws IOException {
            var body = HeatingSchedule.room(roomId, schedule);
            request("PATCH", "/api/rooms/" + roomId, GSON.toJsonTree(body).getAsJsonObject());
        }

        public void livingZoneSchedule(WeeklyScheduleDTO schedule) throws IOException {
            var body = HeatingSchedule.livingZone(schedule);
            request("PATCH", "/api/zones/living", GSON.toJsonTree(body).getAsJsonObject());
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
            byte[] bytes;
            try {
                peer.send(envelope.toString().getBytes(StandardCharsets.UTF_8));
                bytes = peer.receive();
            } catch (IOException e) {
                throw operation.failure(e);
            }
            return response(bytes, id);
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

    static String errorDetails(JsonWebResponseDTO response) {
        JsonObject details = new JsonObject();
        JsonElement description = response.statusDescription;
        if (description != null && description.isJsonPrimitive()) {
            details.add("statusDescription", description);
        }
        JsonElement content = response.content;
        if (content != null && content.isJsonObject()) {
            try {
                ErrorResponseDTO error = GSON.fromJson(content, ErrorResponseDTO.class);
                if (error != null) {
                    JsonObject fields = GSON.toJsonTree(error).getAsJsonObject();
                    var messages = error.errorMessages;
                    if (messages != null) {
                        fields.add("errorMessages", GSON
                                .toJsonTree(messages.stream().filter(java.util.Objects::nonNull).limit(8).toList()));
                    }
                    fields.entrySet().forEach(entry -> details.add(entry.getKey(), entry.getValue()));
                }
            } catch (JsonParseException e) {
                // Malformed diagnostics must not mask the controller's HTTP rejection.
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
            ResponseEnvelopeDTO envelope = GSON.fromJson(json, ResponseEnvelopeDTO.class);
            if (envelope == null || envelope.id == null) {
                throw new IOException("Missing controller response ID");
            }
            if (envelope.id != id) {
                logger.debug("Controller response ID {} does not match request {}", envelope.id, id);
                throw new IOException("Mismatched controller response ID");
            }
            var message = envelope.data;
            if (message == null || !"JsonWebResponse".equals(message.type)) {
                throw new IOException("Missing or unexpected controller response type");
            }
            var response = message.data;
            if (response == null || response.status == null) {
                throw new IOException("Missing controller response status");
            }
            int status = response.status;
            logger.trace("Controller response {}: HTTP {}", id, status);
            if (status < 200 || status >= 300) {
                logger.debug("Controller rejected request {} with HTTP {}", id, status);
                if (logger.isTraceEnabled()) {
                    logger.trace("Controller rejection {} details: {}", id, errorDetails(response));
                }
                throw new ControllerResponseException(status);
            }
            JsonElement content = response.content;
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
}
