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
package org.openhab.binding.nest.internal.nativeapi;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.util.StringContentProvider;
import org.openhab.binding.nest.internal.nativeapi.dto.NativeDtos.AwayRequest;
import org.openhab.binding.nest.internal.nativeapi.dto.NativeDtos.Bucket;
import org.openhab.binding.nest.internal.nativeapi.dto.NativeDtos.Credentials;
import org.openhab.binding.nest.internal.nativeapi.dto.NativeDtos.Cursor;
import org.openhab.binding.nest.internal.nativeapi.dto.NativeDtos.GoogleToken;
import org.openhab.binding.nest.internal.nativeapi.dto.NativeDtos.LaunchRequest;
import org.openhab.binding.nest.internal.nativeapi.dto.NativeDtos.LaunchResponse;
import org.openhab.binding.nest.internal.nativeapi.dto.NativeDtos.NestToken;
import org.openhab.binding.nest.internal.nativeapi.dto.NativeDtos.PathlightRequest;
import org.openhab.binding.nest.internal.nativeapi.dto.NativeDtos.Protect;
import org.openhab.binding.nest.internal.nativeapi.dto.NativeDtos.Session;
import org.openhab.binding.nest.internal.nativeapi.dto.NativeDtos.Structure;
import org.openhab.binding.nest.internal.nativeapi.dto.NativeDtos.SubscribeRequest;
import org.openhab.binding.nest.internal.nativeapi.dto.NativeDtos.SubscribeResponse;
import org.openhab.binding.nest.internal.nativeapi.dto.NativeDtos.Where;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

/**
 * Native Nest protocol. Calls are serialized by the account handler; cancellation may occur on another
 * thread.
 *
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
final class NativeClient {
    static final Gson GSON = new Gson();
    private static final String HOME = "https://home.nest.com";
    private static final String ROTATE = "https://accounts.google.com/RotateCookies";
    private static final long ROTATION_INTERVAL = 600000;
    private final HttpClient http;
    private final Credentials credentials;
    private final Consumer<Credentials> save;
    private final Map<String, Cursor> cursors = new LinkedHashMap<>();
    private final Map<String, Protect> devices = new LinkedHashMap<>();
    private final Map<String, Structure> structures = new LinkedHashMap<>();
    private final Map<String, Map<String, String>> roomsByBucket = new LinkedHashMap<>();
    private final AtomicBoolean wakeRequested = new AtomicBoolean();
    private volatile @Nullable Request activePoll;
    private volatile boolean closed;
    private volatile @Nullable Request active;
    private String token = "";
    private String user = "";
    private String transport = "";
    private long renewAt;

    enum FailureKind {
        AUTHENTICATION,
        COMMUNICATION,
        PROTOCOL,
        TIMEOUT,
        RATE_LIMIT
    }

    static final class Failure extends Exception {
        private static final long serialVersionUID = 1L;
        final FailureKind kind;
        final long retrySeconds;

        Failure(FailureKind kind, String message) {
            this(kind, message, 60);
        }

        Failure(FailureKind kind, String message, long retrySeconds) {
            super(message);
            this.kind = kind;
            this.retrySeconds = retrySeconds;
        }
    }

    NativeClient(HttpClient http, Credentials credentials, Consumer<Credentials> save) {
        NativeCookies.validateTokenUrl(credentials.issueTokenUrl);
        this.http = http;
        this.credentials = credentials;
        this.save = save;
    }

    void cancel() {
        closed = true;
        Request request = active;
        if (request != null) {
            request.abort(new IllegalStateException("Account session closed"));
        }
    }

    void invalidateSession() {
        token = "";
    }

    private static final class PollWakeup extends Exception {
        private static final long serialVersionUID = 1L;
    }

    void wakePoll() {
        wakeRequested.set(true);
        Request request = activePoll;
        if (request != null) {
            request.abort(new PollWakeup());
        }
    }

    Map<String, Structure> structures() {
        return Map.copyOf(structures);
    }

    void setAway(String id, boolean away) throws Failure, InterruptedException {
        if (token.isEmpty() || System.currentTimeMillis() >= renewAt) {
            refresh();
        }
        if (!structures.containsKey(id)) {
            throw new Failure(FailureKind.PROTOCOL, "Structure not present in the account snapshot");
        }
        request(transport + "/v2/put/structure." + encode(id), "POST", headers(),
                GSON.toJson(new AwayRequest(away, Instant.now().getEpochSecond())), false, 30000);
        // A successful write is not an observation. The account refreshes before publishing channel state.
    }

    Map<String, Protect> devices() {
        return Map.copyOf(devices);
    }

    Map<String, String> rooms() {
        Map<String, String> rooms = new LinkedHashMap<>();
        var ambiguous = new HashSet<String>();
        roomsByBucket.values().forEach(bucket -> bucket.forEach((id, name) -> {
            String previous = rooms.put(id, name);
            if (previous != null && !previous.equals(name)) {
                ambiguous.add(id);
            }
        }));
        ambiguous.forEach(rooms::remove);
        return Map.copyOf(rooms);
    }

    void setPathlight(String id, NativePathlightMode mode) throws Failure, InterruptedException {
        if (token.isEmpty() || System.currentTimeMillis() >= renewAt) {
            refresh();
        }
        Protect protect = devices.get(id);
        if (protect == null || !mode.supported(protect)) {
            throw new Failure(FailureKind.PROTOCOL, "Pathlight mode is not supported for this Protect");
        }
        // ALWAYS_ON sets enable=false, continuous=true in the app; send both flags as a single update.
        request(transport + "/v2/put/topaz." + encode(id), "POST", headers(),
                GSON.toJson(
                        new PathlightRequest(mode == NativePathlightMode.ON, mode == NativePathlightMode.ALWAYS_ON)),
                false, 30000);
    }

    void refresh() throws Failure, InterruptedException {
        rotateIfDue();
        if (token.isEmpty() || System.currentTimeMillis() >= renewAt) {
            authenticate();
        }
        LaunchResponse response = json(request(HOME + "/api/0.1/user/" + encode(user) + "/app_launch", "POST",
                headers(), GSON.toJson(new LaunchRequest()), false, 30000), LaunchResponse.class);
        if (response.services == null || response.services.urls == null || response.services.urls.transport == null) {
            throw protocol();
        }
        transport = validateTransport(response.services.urls.transport);
        accept(response.buckets, true);
    }

    void poll() throws Failure, InterruptedException {
        if (wakeRequested.getAndSet(false)) {
            return;
        }
        if (token.isEmpty() || System.currentTimeMillis() >= renewAt) {
            refresh();
            return;
        }
        rotateIfDue();
        long now = System.currentTimeMillis();
        long timeout = Math.max(1,
                Math.min(120000, Math.min(renewAt - now, credentials.lastRotation + ROTATION_INTERVAL - now)));
        String body;
        try {
            body = request(transport + "/v6/subscribe", "POST", headers(),
                    GSON.toJson(new SubscribeRequest(new ArrayList<>(cursors.values()))), false, timeout);
        } catch (Failure e) {
            if (e.kind == FailureKind.TIMEOUT) {
                return;
            }
            throw e;
        }
        if (!body.isBlank()) {
            accept(json(body, SubscribeResponse.class).objects, false);
        }
    }

    private void rotateIfDue() throws Failure, InterruptedException {
        long now = System.currentTimeMillis();
        if (credentials.lastRotation > now) {
            credentials.lastRotation = now;
        }
        if (credentials.lastRotation + ROTATION_INTERVAL > now) {
            return;
        }
        request(ROTATE, "POST", Map.of("Content-Type", "application/json", "Origin", "https://accounts.google.com"),
                GSON.toJson(List.of(0, "-0000000000000000000")), true, 30000);
    }

    private void authenticate() throws Failure, InterruptedException {
        GoogleToken google = json(request(credentials.issueTokenUrl, "GET",
                Map.of("Referer", "https://accounts.google.com/o/oauth2/iframe", "X-Requested-With", "XmlHttpRequest"),
                "", true, 30000), GoogleToken.class);
        if (google.error != null) {
            if (List.of("USER_LOGGED_OUT", "invalid_grant", "access_denied").contains(google.error)) {
                throw new Failure(FailureKind.AUTHENTICATION, "Google session rejected; use the authenticate action");
            }
            throw new Failure(FailureKind.COMMUNICATION, "Google token request failed");
        }
        if (google.accessToken == null || google.accessToken.isBlank()) {
            throw protocol();
        }
        NestToken nest = json(request("https://nestauthproxyservice-pa.googleapis.com/v1/issue_jwt", "POST",
                Map.of("Authorization", "Bearer " + google.accessToken, "Referer", HOME, "Content-Type",
                        "application/x-www-form-urlencoded"),
                "google_oauth_access_token=" + encode(google.accessToken)
                        + "&policy_id=authproxy-oauth-policy&embed_google_oauth_access_token=true&expire_after=3600s",
                false, 30000), NestToken.class);
        if (nest.jwt == null || nest.jwt.isBlank()) {
            throw protocol();
        }
        Session session = json(
                request(HOME + "/session", "GET",
                        Map.of("Authorization", "Basic " + nest.jwt, "Cookie",
                                "G_ENABLED_IDPS=google; eu_cookie_accepted=1; cztoken=" + nest.jwt),
                        "", false, 30000),
                Session.class);
        if (session.userid == null || session.userid.isBlank() || session.accessToken == null
                || session.accessToken.isBlank()) {
            throw protocol();
        }
        token = session.accessToken;
        user = session.userid;
        long lifetime = google.expiresIn == null || google.expiresIn <= 0 ? 3600 : Math.min(3600, google.expiresIn);
        long expiry = System.currentTimeMillis() + lifetime * 1000;
        if (nest.claims != null && nest.claims.expirationTime != null) {
            try {
                expiry = Math.min(expiry, Instant.parse(nest.claims.expirationTime).toEpochMilli());
            } catch (DateTimeParseException e) {
                throw protocol();
            }
        }
        // /session reports 30 days even though the actual Nest token stopped working after one hour.
        renewAt = expiry - 60000;
        if (renewAt <= System.currentTimeMillis()) {
            throw protocol();
        }
    }

    private Map<String, String> headers() {
        return Map.of("Authorization", "Basic " + token, "X-nl-user-id", user, "X-nl-protocol-version", "1",
                "Content-Type", "application/json");
    }

    private String request(String url, String method, Map<String, String> headers, String body, boolean google,
            long timeout) throws Failure, InterruptedException {
        Request request = http.newRequest(url).method(method).followRedirects(false).timeout(timeout,
                TimeUnit.MILLISECONDS);
        request.header("User-Agent", "Mozilla/5.0");
        headers.forEach(request::header);
        if (google) {
            request.header("Cookie", NativeCookies.header(credentials, URI.create(url), System.currentTimeMillis()));
        }
        if (!body.isEmpty()) {
            request.content(new StringContentProvider(headers.getOrDefault("Content-Type", "application/json"), body,
                    StandardCharsets.UTF_8));
        }
        active = request;
        boolean subscription = url.endsWith("/v6/subscribe");
        if (subscription) {
            activePoll = request;
        }
        if (closed) {
            request.abort(new IllegalStateException("Account session closed"));
            throw protocol();
        }
        try {
            if (subscription && wakeRequested.getAndSet(false)) {
                request.abort(new PollWakeup());
                return "";
            }
            ContentResponse response = request.send();
            if (closed) {
                throw protocol();
            }
            int status = response.getStatus();
            if (google) {
                List<String> cookies = response.getHeaders().getValuesList("Set-Cookie");
                boolean timestamp;
                try {
                    timestamp = NativeCookies.merge(credentials, URI.create(url), cookies, System.currentTimeMillis());
                } catch (IllegalArgumentException e) {
                    throw protocol();
                }
                if (url.equals(ROTATE) && status == 200 && timestamp) {
                    credentials.lastRotation = System.currentTimeMillis();
                }
                if (!cookies.isEmpty()) {
                    save.accept(credentials);
                }
                if (url.equals(ROTATE) && status == 200 && !timestamp) {
                    throw protocol();
                }
            }
            if (status == 429) {
                long retry = 600;
                String value = response.getHeaders().get("Retry-After");
                if (value != null) {
                    try {
                        retry = Math.max(retry, Long.parseLong(value));
                    } catch (NumberFormatException e) {
                        /* Keep the conservative rotation interval. */ }
                }
                throw new Failure(FailureKind.RATE_LIMIT, "Service rate limit; retry scheduled",
                        Math.min(retry, 86400));
            }
            if (status == 401 || status == 403) {
                token = "";
                throw new Failure(google ? FailureKind.AUTHENTICATION : FailureKind.COMMUNICATION,
                        google ? "Google session rejected; use the authenticate action"
                                : "Nest session rejected; retry scheduled");
            }
            if (status < 200 || status >= 300) {
                throw new Failure(FailureKind.COMMUNICATION, "Service returned HTTP " + status);
            }
            return response.getContentAsString();
        } catch (TimeoutException e) {
            throw new Failure(FailureKind.TIMEOUT, "Request timed out");
        } catch (java.util.concurrent.ExecutionException e) {
            if (subscription && e.getCause() instanceof PollWakeup) {
                return "";
            }
            if (e.getCause() instanceof TimeoutException) {
                throw new Failure(FailureKind.TIMEOUT, "Request timed out");
            }
            throw new Failure(FailureKind.COMMUNICATION, "Request failed");
        } finally {
            activePoll = null;
            active = null;
        }
    }

    static String validateTransport(String value) throws Failure {
        try {
            URI uri = URI.create(value);
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || !uri.getHost().endsWith(".nest.com")
                    || uri.getPort() != -1 || uri.getUserInfo() != null || uri.getQuery() != null
                    || uri.getFragment() != null || !(uri.getPath().isEmpty() || "/".equals(uri.getPath()))) {
                throw protocol();
            }
            return "https://" + uri.getHost();
        } catch (IllegalArgumentException e) {
            throw protocol();
        }
    }

    void accept(@Nullable List<@Nullable Bucket> buckets, boolean snapshot) throws Failure {
        if (buckets == null) {
            throw protocol();
        }
        Map<String, Cursor> nextCursors = snapshot ? new LinkedHashMap<>() : new LinkedHashMap<>(cursors);
        Map<String, Protect> nextDevices = snapshot ? new LinkedHashMap<>() : new LinkedHashMap<>(devices);
        Map<String, Structure> nextStructures = snapshot ? new LinkedHashMap<>() : new LinkedHashMap<>(structures);
        Map<String, Map<String, String>> nextRooms = snapshot ? new LinkedHashMap<>()
                : new LinkedHashMap<>(roomsByBucket);
        for (@Nullable
        Bucket bucket : buckets) {
            if (bucket == null || bucket.key == null) {
                throw protocol();
            }
            if (!(bucket.key.startsWith("topaz.") || bucket.key.startsWith("structure.")
                    || bucket.key.startsWith("where.") || bucket.key.startsWith("user."))) {
                continue;
            }
            if (bucket.revision == null || bucket.timestamp == null || bucket.value == null) {
                throw protocol();
            }
            nextCursors.put(bucket.key, new Cursor(bucket.key, bucket.revision, bucket.timestamp));
            if (bucket.key.startsWith("topaz.")) {
                String id = bucket.key.substring(6);
                if (id.isBlank()) {
                    throw protocol();
                }
                for (String field : List.of("night_light_enable", "night_light_continuous")) {
                    var flag = bucket.value.get(field);
                    if (flag != null && !flag.isJsonNull()
                            && !(flag.isJsonPrimitive() && flag.getAsJsonPrimitive().isBoolean())) {
                        throw protocol();
                    }
                }
                nextDevices.put(id, merge(nextDevices.get(id), json(bucket.value.toString(), Protect.class)));
            } else if (bucket.key.startsWith("structure.")) {
                String id = bucket.key.substring(10);
                if (id.isBlank()) {
                    throw protocol();
                }
                var away = bucket.value.get("away");
                if (away != null && !away.isJsonNull()
                        && !(away.isJsonPrimitive() && away.getAsJsonPrimitive().isBoolean())) {
                    throw protocol();
                }
                Structure update = json(bucket.value.toString(), Structure.class);
                Structure old = nextStructures.get(id);
                Structure structure = new Structure();
                structure.name = update.name != null ? update.name : old == null ? null : old.name;
                structure.away = update.away != null ? update.away : old == null ? null : old.away;
                nextStructures.put(id, structure);
            } else if (bucket.key.startsWith("where.")) {
                try {
                    Where where = json(bucket.value.toString(), Where.class);
                    if (where.wheres != null) {
                        Map<String, String> rooms = new LinkedHashMap<>();
                        for (var room : where.wheres) {
                            if (room != null && room.id != null && !room.id.isBlank() && room.name != null
                                    && !room.name.isBlank()) {
                                rooms.put(room.id, room.name);
                            }
                        }
                        nextRooms.put(bucket.key, Map.copyOf(rooms));
                    }
                } catch (Failure e) {
                    // Room names are optional enrichment and must not interrupt Protect observations.
                    nextRooms.remove(bucket.key);
                }
            }
        }
        cursors.clear();
        cursors.putAll(nextCursors);
        devices.clear();
        devices.putAll(nextDevices);
        structures.clear();
        structures.putAll(nextStructures);
        roomsByBucket.clear();
        roomsByBucket.putAll(nextRooms);
    }

    private static Protect merge(@Nullable Protect old, Protect update) {
        Protect p = new Protect();
        p.whereId = update.whereId != null ? update.whereId : old == null ? null : old.whereId;
        p.nightLightEnabled = update.nightLightEnabled != null ? update.nightLightEnabled
                : old == null ? null : old.nightLightEnabled;
        p.nightLightContinuous = update.nightLightContinuous != null ? update.nightLightContinuous
                : old == null ? null : old.nightLightContinuous;
        p.powerType = update.powerType != null ? update.powerType : old == null ? null : old.powerType;
        p.smoke = update.smoke != null ? update.smoke : old == null ? null : old.smoke;
        p.carbonMonoxide = update.carbonMonoxide != null ? update.carbonMonoxide
                : old == null ? null : old.carbonMonoxide;
        p.battery = update.battery != null ? update.battery : old == null ? null : old.battery;
        p.linePower = update.linePower != null ? update.linePower : old == null ? null : old.linePower;
        p.online = update.online != null ? update.online : old == null ? null : old.online;
        p.replaceBy = update.replaceBy != null ? update.replaceBy : old == null ? null : old.replaceBy;
        p.testStarted = update.testStarted != null ? update.testStarted : old == null ? null : old.testStarted;
        p.testEnded = update.testEnded != null ? update.testEnded : old == null ? null : old.testEnded;
        return p;
    }

    private static <T> T json(String body, Class<T> type) throws Failure {
        try {
            @Nullable
            T result = GSON.fromJson(body, type);
            if (result == null) {
                throw protocol();
            }
            return result;
        } catch (JsonParseException e) {
            throw protocol();
        }
    }

    private static Failure protocol() {
        return new Failure(FailureKind.PROTOCOL, "Unexpected native API response");
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
