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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentProvider;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.http.HttpFields;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.openhab.binding.nest.internal.nativeapi.dto.NativeDtos;
import org.openhab.core.types.UnDefType;

/**
 * Tests protocol transitions with synthetic credentials and DTO payloads only.
 *
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
class NativeClientTest {
    @Test
    void roomMetadataSurvivesPartialUpdatesAndTracksRenamesAndRemoval() throws Exception {
        var client = new NativeClient(mock(HttpClient.class), NativeCookies.capture(NativeCookiesTest.URL, "SID=s"),
                ignored -> {
                });
        var snapshot = Objects.requireNonNull(NativeClient.GSON.fromJson("""
                {"updated_buckets":[
                {"object_key":"topaz.device","object_revision":1,"object_timestamp":10,
                 "value":{"where_id":"hall","smoke_status":0}},
                {"object_key":"where.home","object_revision":1,"object_timestamp":10,
                 "value":{"wheres":[{"where_id":"hall","name":"Hallway"},null,{"where_id":"blank","name":" "}]}}]}
                """, NativeDtos.LaunchResponse.class));
        client.accept(snapshot.buckets, true);
        assertEquals("Hallway", client.rooms().get("hall"));
        assertEquals(1, client.rooms().size());
        assertEquals("hall", Objects.requireNonNull(client.devices().get("device")).whereId);
        client.accept(
                Objects.requireNonNull(NativeClient.GSON.fromJson(UPDATE, NativeDtos.SubscribeResponse.class)).objects,
                false);
        assertEquals("hall", Objects.requireNonNull(client.devices().get("device")).whereId);
        assertEquals("Hallway", client.rooms().get("hall"));
        var where = snapshot.buckets.get(1);
        where.value = new com.google.gson.JsonObject();
        client.accept(List.of(where), false);
        assertEquals("Hallway", client.rooms().get("hall"));
        where.value = NativeClient.GSON.fromJson("""
                {"wheres":[{"where_id":"hall","name":"Landing"}]}
                """, com.google.gson.JsonObject.class);
        client.accept(List.of(where), false);
        assertEquals("Landing", client.rooms().get("hall"));
        where.value = NativeClient.GSON.fromJson("{\"wheres\":[]}", com.google.gson.JsonObject.class);
        client.accept(List.of(where), false);
        assertTrue(client.rooms().isEmpty());
        where.value = NativeClient.GSON.fromJson("""
                {"wheres":[{"where_id":"hall","name":"Hallway"}]}
                """, com.google.gson.JsonObject.class);
        client.accept(snapshot.buckets, true);
        assertEquals("Hallway", client.rooms().get("hall"));
        client.accept(List.of(), true);
        assertTrue(client.rooms().isEmpty());
    }

    @Test
    void malformedRoomMetadataDoesNotInterruptProtectObservations() throws Exception {
        var client = new NativeClient(mock(HttpClient.class), NativeCookies.capture(NativeCookiesTest.URL, "SID=s"),
                ignored -> {
                });
        var snapshot = Objects.requireNonNull(NativeClient.GSON.fromJson("""
                {"updated_buckets":[
                {"object_key":"topaz.device","object_revision":1,"object_timestamp":10,"value":{"smoke_status":3}},
                {"object_key":"where.home","object_revision":1,"object_timestamp":10,"value":{"wheres":false}}]}
                """, NativeDtos.LaunchResponse.class));
        client.accept(snapshot.buckets, true);
        assertEquals(3, Objects.requireNonNull(client.devices().get("device")).smoke);
        assertTrue(client.rooms().isEmpty());
    }

    @Test
    void brightnessObservationsPreservePartialStateAndUnknownValues() throws Exception {
        var client = new NativeClient(mock(HttpClient.class), NativeCookies.capture(NativeCookiesTest.URL, "SID=s"),
                ignored -> {
                });
        var snapshot = Objects
                .requireNonNull(NativeClient.GSON.fromJson(PATHLIGHT_SNAPSHOT, NativeDtos.LaunchResponse.class));
        var bucket = snapshot.buckets.getFirst();
        for (int value : new int[] { 1, 2, 3, 0, 99 }) {
            bucket.value = NativeClient.GSON.fromJson("{\"night_light_brightness\":" + value + "}",
                    com.google.gson.JsonObject.class);
            client.accept(List.of(bucket), false);
            assertEquals(value, Objects.requireNonNull(client.devices().get("device")).nightLightBrightness);
            bucket.value = new com.google.gson.JsonObject();
            client.accept(List.of(bucket), false);
            assertEquals(value, Objects.requireNonNull(client.devices().get("device")).nightLightBrightness);
        }
        assertNull(NativePathlightBrightness.observed(null));
        assertNull(NativePathlightBrightness.observed(0));
        assertNull(NativePathlightBrightness.observed(99));
        assertEquals(NativePathlightBrightness.LOW, NativePathlightBrightness.observed(1));
        assertEquals(NativePathlightBrightness.MEDIUM, NativePathlightBrightness.observed(2));
        assertEquals(NativePathlightBrightness.HIGH, NativePathlightBrightness.observed(3));
        client.accept(List.of(bucket), true);
        assertNull(Objects.requireNonNull(client.devices().get("device")).nightLightBrightness);
    }

    private static final String PATHLIGHT_SNAPSHOT = """
            {"service_urls":{"urls":{"transport_url":"https://transport.nest.com"}},
             "updated_buckets":[{"object_key":"topaz.device","object_revision":1,"object_timestamp":10,
             "value":{"night_light_enable":false,"night_light_continuous":false,"wired_or_battery":0}}]}
            """;

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = { false, true })
    void nightlyPromiseWritesOnlyEnableFlagWithoutPredictingState(boolean enabled) throws Exception {
        var state = NativeCookies.capture(NativeCookiesTest.URL, "SID=s");
        state.lastRotation = System.currentTimeMillis();
        var requests = new ArrayList<Request>();
        var http = transport(requests, response(200, "{\"access_token\":\"google\"}"),
                response(200, "{\"jwt\":\"nest\"}"), response(200, "{\"userid\":\"u\",\"access_token\":\"session\"}"),
                response(200, PATHLIGHT_SNAPSHOT), response(200, "{}"), response(503, "private failure"));
        var client = new NativeClient(http, state, ignored -> {
        });
        client.refresh();
        client.setNightlyPromise("device", enabled);
        verify(http).newRequest("https://transport.nest.com/v2/put/topaz.device");
        Request write = requests.getLast();
        verify(write).method("POST");
        verify(write).header("Authorization", "Basic session");
        var content = ArgumentCaptor.forClass(ContentProvider.class);
        verify(write).content(content.capture());
        var body = new StringBuilder();
        content.getValue().forEach(buffer -> body.append(java.nio.charset.StandardCharsets.UTF_8.decode(buffer)));
        var dto = Objects
                .requireNonNull(NativeClient.GSON.fromJson(body.toString(), NativeDtos.NightlyPromiseRequest.class));
        assertEquals(enabled, dto.enabled);
        var json = Objects
                .requireNonNull(NativeClient.GSON.fromJson(body.toString(), com.google.gson.JsonObject.class));
        assertEquals(java.util.Set.of("ntp_green_led_enable"), json.keySet());
        assertNull(Objects.requireNonNull(client.devices().get("device")).nightlyPromiseEnabled);
        assertEquals(NativePathlightMode.OFF,
                NativePathlightMode.observed(Objects.requireNonNull(client.devices().get("device"))));
        assertThrows(NativeClient.Failure.class, () -> client.setNightlyPromise("missing", enabled));
        assertEquals(5, requests.size());
        assertThrows(NativeClient.Failure.class, () -> client.setNightlyPromise("device", enabled));
        assertEquals(NativePathlightMode.OFF,
                NativePathlightMode.observed(Objects.requireNonNull(client.devices().get("device"))));
    }

    @ParameterizedTest
    @CsvSource({ "LOW,1", "MEDIUM,2", "HIGH,3" })
    void brightnessWritesBothLightsWithoutPredictingState(NativePathlightBrightness brightness, int expected)
            throws Exception {
        var state = NativeCookies.capture(NativeCookiesTest.URL, "SID=s");
        state.lastRotation = System.currentTimeMillis();
        var requests = new ArrayList<Request>();
        var http = transport(requests, response(200, "{\"access_token\":\"google\"}"),
                response(200, "{\"jwt\":\"nest\"}"), response(200, "{\"userid\":\"u\",\"access_token\":\"session\"}"),
                response(200, PATHLIGHT_SNAPSHOT), response(200, "{}"), response(503, "private failure"));
        var client = new NativeClient(http, state, ignored -> {
        });
        client.refresh();
        client.setPathlightBrightness("device", brightness);
        verify(http).newRequest("https://transport.nest.com/v2/put/topaz.device");
        Request write = requests.getLast();
        verify(write).method("POST");
        verify(write).header("Authorization", "Basic session");
        var content = ArgumentCaptor.forClass(ContentProvider.class);
        verify(write).content(content.capture());
        var body = new StringBuilder();
        content.getValue().forEach(buffer -> body.append(java.nio.charset.StandardCharsets.UTF_8.decode(buffer)));
        var dto = Objects
                .requireNonNull(NativeClient.GSON.fromJson(body.toString(), NativeDtos.BrightnessRequest.class));
        assertEquals(expected, dto.pathlight);
        assertEquals(expected, dto.nightlyPromise);
        assertFalse(body.toString().contains("night_light_enable"));
        assertNull(Objects.requireNonNull(client.devices().get("device")).nightLightBrightness);
        assertEquals(NativePathlightMode.OFF,
                NativePathlightMode.observed(Objects.requireNonNull(client.devices().get("device"))));
        assertThrows(NativeClient.Failure.class, () -> client.setPathlightBrightness("missing", brightness));
        assertEquals(5, requests.size());
        assertThrows(NativeClient.Failure.class, () -> client.setPathlightBrightness("device", brightness));
        assertEquals(NativePathlightMode.OFF,
                NativePathlightMode.observed(Objects.requireNonNull(client.devices().get("device"))));
    }

    @ParameterizedTest
    @CsvSource({ "OFF,false,false", "ON,true,false", "ALWAYS_ON,false,true" })
    void pathlightWritesBothFlagsWithoutPredictingState(NativePathlightMode mode, boolean enabled, boolean continuous)
            throws Exception {
        var state = NativeCookies.capture(NativeCookiesTest.URL, "SID=s");
        state.lastRotation = System.currentTimeMillis();
        var requests = new ArrayList<Request>();
        var http = transport(requests, response(200, "{\"access_token\":\"google\"}"),
                response(200, "{\"jwt\":\"nest\"}"), response(200, "{\"userid\":\"u\",\"access_token\":\"session\"}"),
                response(200, PATHLIGHT_SNAPSHOT), response(200, "{}"), response(503, "private failure"));
        var client = new NativeClient(http, state, ignored -> {
        });
        client.refresh();
        client.setPathlight("device", mode);
        verify(http).newRequest("https://transport.nest.com/v2/put/topaz.device");
        Request write = requests.getLast();
        verify(write).method("POST");
        verify(write).header("Authorization", "Basic session");
        var content = ArgumentCaptor.forClass(ContentProvider.class);
        verify(write).content(content.capture());
        var body = new StringBuilder();
        content.getValue().forEach(buffer -> body.append(java.nio.charset.StandardCharsets.UTF_8.decode(buffer)));
        var dto = Objects
                .requireNonNull(NativeClient.GSON.fromJson(body.toString(), NativeDtos.PathlightRequest.class));
        assertEquals(enabled, dto.enabled);
        assertEquals(continuous, dto.continuous);
        assertEquals(NativePathlightMode.OFF,
                NativePathlightMode.observed(Objects.requireNonNull(client.devices().get("device"))));
        assertThrows(NativeClient.Failure.class, () -> client.setPathlight("missing", mode));
        assertEquals(5, requests.size());
        assertThrows(NativeClient.Failure.class, () -> client.setPathlight("device", mode));
        assertEquals(NativePathlightMode.OFF,
                NativePathlightMode.observed(Objects.requireNonNull(client.devices().get("device"))));
    }

    @Test
    void nightlyPromiseUpdatesMergeAndRejectInvalidFlagsAtomically() throws Exception {
        var client = new NativeClient(mock(HttpClient.class), NativeCookies.capture(NativeCookiesTest.URL, "SID=s"),
                ignored -> {
                });
        var snapshot = Objects
                .requireNonNull(NativeClient.GSON.fromJson(PATHLIGHT_SNAPSHOT, NativeDtos.LaunchResponse.class));
        var bucket = snapshot.buckets.getFirst();
        client.accept(snapshot.buckets, true);
        assertNull(Objects.requireNonNull(client.devices().get("device")).nightlyPromiseEnabled);
        for (boolean enabled : new boolean[] { true, false }) {
            bucket.value = new com.google.gson.JsonObject();
            bucket.value.addProperty("ntp_green_led_enable", enabled);
            client.accept(List.of(bucket), false);
            assertEquals(enabled, Objects.requireNonNull(client.devices().get("device")).nightlyPromiseEnabled);
            bucket.value = new com.google.gson.JsonObject();
            client.accept(List.of(bucket), false);
            assertEquals(enabled, Objects.requireNonNull(client.devices().get("device")).nightlyPromiseEnabled);
        }
        bucket.value.addProperty("ntp_green_led_enable", "invalid");
        assertThrows(NativeClient.Failure.class, () -> client.accept(List.of(bucket), false));
        assertEquals(false, Objects.requireNonNull(client.devices().get("device")).nightlyPromiseEnabled);
        bucket.value.addProperty("ntp_green_led_enable", true);
        client.accept(List.of(bucket), true);
        assertEquals(true, Objects.requireNonNull(client.devices().get("device")).nightlyPromiseEnabled);
        bucket.value = new com.google.gson.JsonObject();
        client.accept(List.of(bucket), true);
        assertNull(Objects.requireNonNull(client.devices().get("device")).nightlyPromiseEnabled);
    }

    @Test
    void pathlightUpdatesMergeAndRejectInvalidFlagsAtomically() throws Exception {
        var client = new NativeClient(mock(HttpClient.class), NativeCookies.capture(NativeCookiesTest.URL, "SID=s"),
                ignored -> {
                });
        var snapshot = Objects
                .requireNonNull(NativeClient.GSON.fromJson(PATHLIGHT_SNAPSHOT, NativeDtos.LaunchResponse.class));
        client.accept(snapshot.buckets, true);
        var update = Objects.requireNonNull(NativeClient.GSON.fromJson("""
                {"objects":[{"object_key":"topaz.device","object_revision":2,"object_timestamp":11,
                "value":{"night_light_enable":true}}]}
                """, NativeDtos.SubscribeResponse.class));
        client.accept(update.objects, false);
        var protect = Objects.requireNonNull(client.devices().get("device"));
        assertEquals(NativePathlightMode.ON, NativePathlightMode.observed(protect));
        assertEquals(0, protect.powerType);
        update.objects.getFirst().value.addProperty("night_light_continuous", true);
        client.accept(update.objects, false);
        assertEquals(NativePathlightMode.ALWAYS_ON,
                NativePathlightMode.observed(Objects.requireNonNull(client.devices().get("device"))));
        update.objects.getFirst().value.addProperty("night_light_enable", "invalid");
        assertThrows(NativeClient.Failure.class, () -> client.accept(update.objects, false));
        assertEquals(NativePathlightMode.ALWAYS_ON,
                NativePathlightMode.observed(Objects.requireNonNull(client.devices().get("device"))));
        client.accept(snapshot.buckets, true);
        assertEquals(NativePathlightMode.OFF,
                NativePathlightMode.observed(Objects.requireNonNull(client.devices().get("device"))));
    }

    @Test
    void continuousPathlightRequiresKnownWiredModelNotPresentLinePower() throws Exception {
        var protect = new NativeDtos.Protect();
        assertNull(NativePathlightMode.observed(protect));
        protect.nightLightEnabled = true;
        assertNull(NativePathlightMode.observed(protect));
        assertFalse(NativePathlightMode.ALWAYS_ON.supported(protect));
        assertTrue(NativePathlightMode.ON.supported(protect));
        assertTrue(NativePathlightMode.OFF.supported(protect));
        protect.powerType = 1;
        protect.linePower = true;
        assertFalse(NativePathlightMode.ALWAYS_ON.supported(protect));
        protect.powerType = 0;
        protect.linePower = false;
        assertTrue(NativePathlightMode.ALWAYS_ON.supported(protect));
        var state = NativeCookies.capture(NativeCookiesTest.URL, "SID=s");
        state.lastRotation = System.currentTimeMillis();
        var requests = new ArrayList<Request>();
        var client = new NativeClient(
                transport(requests, response(200, "{\"access_token\":\"google\"}"), response(200, "{\"jwt\":\"nest\"}"),
                        response(200, "{\"userid\":\"u\",\"access_token\":\"session\"}"),
                        response(200, PATHLIGHT_SNAPSHOT.replace("\"wired_or_battery\":0", "\"wired_or_battery\":1"))),
                state, ignored -> {
                });
        client.refresh();
        assertThrows(NativeClient.Failure.class, () -> client.setPathlight("device", NativePathlightMode.ALWAYS_ON));
        assertEquals(4, requests.size());
    }

    private static final String STRUCTURE_SNAPSHOT = """
            {"service_urls":{"urls":{"transport_url":"https://transport.nest.com"}},
             "updated_buckets":[{"object_key":"structure.home","object_revision":1,"object_timestamp":10,
             "value":{"name":"My Home","away":false}}]}
            """;

    @Test
    void structureUpdatesMergeAtomicallyAndSnapshotRemovesMissingHomes() throws Exception {
        var client = new NativeClient(mock(HttpClient.class), NativeCookies.capture(NativeCookiesTest.URL, "SID=s"),
                ignored -> {
                });
        client.accept(Objects.requireNonNull(
                NativeClient.GSON.fromJson(STRUCTURE_SNAPSHOT, NativeDtos.LaunchResponse.class)).buckets, true);
        assertEquals(false, Objects.requireNonNull(client.structures().get("home")).away);
        var update = Objects.requireNonNull(NativeClient.GSON.fromJson("""
                {"objects":[{"object_key":"structure.home","object_revision":2,"object_timestamp":11,
                "value":{"away":true}}]}
                """, NativeDtos.SubscribeResponse.class));
        client.accept(update.objects, false);
        assertEquals("My Home", Objects.requireNonNull(client.structures().get("home")).name);
        assertEquals(true, Objects.requireNonNull(client.structures().get("home")).away);
        update.objects.getFirst().value.addProperty("away", "unknown");
        assertThrows(NativeClient.Failure.class, () -> client.accept(update.objects, false));
        assertEquals(true, Objects.requireNonNull(client.structures().get("home")).away);
        update.objects.getFirst().value.remove("away");
        client.accept(update.objects, true);
        assertNull(Objects.requireNonNull(client.structures().get("home")).away);
        client.accept(List.of(), true);
        assertTrue(client.structures().isEmpty());
    }

    @Test
    void awayWritesUseKnownStructureAndDoNotOptimisticallyUpdateState() throws Exception {
        var state = NativeCookies.capture(NativeCookiesTest.URL, "SID=s");
        state.lastRotation = System.currentTimeMillis();
        var requests = new ArrayList<Request>();
        var http = transport(requests, response(200, "{\"access_token\":\"google\"}"),
                response(200, "{\"jwt\":\"nest\"}"), response(200, "{\"userid\":\"u\",\"access_token\":\"session\"}"),
                response(200, STRUCTURE_SNAPSHOT), response(200, "{}"), response(503, "private failure"),
                response(200, STRUCTURE_SNAPSHOT.replace("false", "true")));
        var client = new NativeClient(http, state, ignored -> {
        });
        client.refresh();
        client.setAway("home", true);
        verify(http).newRequest("https://transport.nest.com/v2/put/structure.home");
        Request write = requests.getLast();
        verify(write).method("POST");
        verify(write).header("Authorization", "Basic session");
        var content = ArgumentCaptor.forClass(ContentProvider.class);
        verify(write).content(content.capture());
        var body = new StringBuilder();
        content.getValue().forEach(buffer -> body.append(java.nio.charset.StandardCharsets.UTF_8.decode(buffer)));
        var dto = Objects.requireNonNull(NativeClient.GSON.fromJson(body.toString(), NativeDtos.AwayRequest.class));
        assertTrue(dto.away);
        assertEquals(0, dto.setter);
        assertTrue(dto.timestamp > 0);
        assertEquals(false, Objects.requireNonNull(client.structures().get("home")).away);
        assertThrows(NativeClient.Failure.class, () -> client.setAway("unobserved", true));
        assertEquals(5, requests.size());
        assertThrows(NativeClient.Failure.class, () -> client.setAway("home", false));
        assertEquals(false, Objects.requireNonNull(client.structures().get("home")).away);
        client.refresh();
        assertEquals(true, Objects.requireNonNull(client.structures().get("home")).away);
    }

    @Test
    void wakingPollDoesNotCloseSessionOrAbortWrites() throws Exception {
        var state = NativeCookies.capture(NativeCookiesTest.URL, "SID=s");
        state.lastRotation = System.currentTimeMillis();
        var requests = new ArrayList<Request>();
        var http = transport(requests, response(200, "{\"access_token\":\"google\"}"),
                response(200, "{\"jwt\":\"nest\"}"), response(200, "{\"userid\":\"u\",\"access_token\":\"session\"}"),
                response(200, STRUCTURE_SNAPSHOT));
        var client = new NativeClient(http, state, ignored -> {
        });
        client.refresh();
        Request poll = mock(Request.class, RETURNS_SELF);
        doReturn(poll).when(http).newRequest(endsWith("/v6/subscribe"));
        var aborted = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        when(poll.abort(any())).thenAnswer(invocation -> {
            aborted.set(invocation.getArgument(0));
            return true;
        });
        when(poll.send()).thenAnswer(invocation -> {
            client.wakePoll();
            throw new ExecutionException(aborted.get());
        });
        client.poll();
        verify(poll).abort(any());
        Request write = mock(Request.class, RETURNS_SELF);
        doReturn(write).when(http).newRequest(contains("/v2/put/"));
        when(write.send()).thenAnswer(invocation -> {
            client.wakePoll();
            return response(200, "{}");
        });
        client.setAway("home", false);
        verify(write, never()).abort(any());
        assertEquals(false, Objects.requireNonNull(client.structures().get("home")).away);
    }

    private static final String SNAPSHOT = """
            {"service_urls":{"urls":{"transport_url":"https://transport.nest.com"}},
             "updated_buckets":[{"object_key":"topaz.device","object_revision":1,"object_timestamp":10,
             "value":{"smoke_status":0,"co_status":0,"line_power_present":true}}]}
            """;
    private static final String UPDATE = """
            {"objects":[{"object_key":"topaz.device","object_revision":2,"object_timestamp":11,
             "value":{"co_status":3}}]}
            """;

    @Test
    void authenticationSnapshotPartialUpdateAndTimeout() throws Exception {
        var state = NativeCookies.capture(NativeCookiesTest.URL, "SID=secret");
        state.lastRotation = System.currentTimeMillis();
        var requests = new ArrayList<Request>();
        var http = transport(requests, response(200, "{\"access_token\":\"google\",\"expires_in\":3600}"),
                response(200, "{\"jwt\":\"nest\"}"),
                response(200, "{\"userid\":\"user\",\"access_token\":\"session\"}"), response(200, SNAPSHOT),
                response(200, UPDATE));
        var client = new NativeClient(http, state, ignored -> {
        });
        client.refresh();
        assertEquals(0, Objects.requireNonNull(client.devices().get("device")).smoke);
        client.poll();
        assertEquals(0, Objects.requireNonNull(client.devices().get("device")).smoke);
        assertEquals(3, Objects.requireNonNull(client.devices().get("device")).carbonMonoxide);
        assertTrue(Objects.requireNonNull(client.devices().get("device")).linePower);
        for (Request request : requests) {
            verify(request).followRedirects(false);
        }
        verify(requests.get(3)).header("Authorization", "Basic session");
        Request timeout = mock(Request.class, RETURNS_SELF);
        doReturn(timeout).when(http).newRequest(anyString());
        when(timeout.send()).thenThrow(new TimeoutException(), new ExecutionException(new TimeoutException()));
        client.poll();
        client.poll();
        assertEquals(3, Objects.requireNonNull(client.devices().get("device")).carbonMonoxide);
    }

    @Test
    void malformedUpdatesAreAtomicAndSnapshotCanRecover() throws Exception {
        var client = new NativeClient(mock(HttpClient.class), NativeCookies.capture(NativeCookiesTest.URL, "SID=s"),
                ignored -> {
                });
        client.accept(
                Objects.requireNonNull(NativeClient.GSON.fromJson(SNAPSHOT, NativeDtos.LaunchResponse.class)).buckets,
                true);
        var update = Objects.requireNonNull(NativeClient.GSON.fromJson(UPDATE, NativeDtos.SubscribeResponse.class));
        update.objects.add(new NativeDtos.Bucket());
        assertThrows(NativeClient.Failure.class, () -> client.accept(update.objects, false));
        assertEquals(0, Objects.requireNonNull(client.devices().get("device")).carbonMonoxide);
        client.accept(List.of(), true);
        assertTrue(client.devices().isEmpty());
    }

    @Test
    void rejectedGoogleSessionPersistsReceivedCookiesAndStops() throws Exception {
        var state = NativeCookies.capture(NativeCookiesTest.URL, "SID=old");
        state.lastRotation = System.currentTimeMillis();
        ContentResponse rejected = response(200, "{\"error\":\"USER_LOGGED_OUT\"}");
        rejected.getHeaders().add("Set-Cookie", "SID=new; Secure; Path=/");
        var saved = new ArrayList<String>();
        var requests = new ArrayList<Request>();
        var client = new NativeClient(transport(requests, rejected), state,
                value -> saved.add(NativeClient.GSON.toJson(value)));
        var failure = assertThrows(NativeClient.Failure.class, client::refresh);
        assertEquals(NativeClient.FailureKind.AUTHENTICATION, failure.kind);
        assertEquals(1, requests.size());
        assertEquals("new",
                Objects.requireNonNull(
                        NativeClient.GSON.fromJson(saved.getFirst(), NativeDtos.Credentials.class)).cookies
                        .getFirst().value);
        assertFalse(Objects.requireNonNull(failure.getMessage()).contains("SID"));
    }

    @Test
    void rateLimitDoesNotAdvanceRotationTime() throws Exception {
        var state = NativeCookies.capture(NativeCookiesTest.URL, "SID=old");
        ContentResponse limited = response(429, "private response");
        limited.getHeaders().add("Retry-After", "1200");
        var client = new NativeClient(transport(new ArrayList<>(), limited), state, ignored -> {
        });
        var failure = assertThrows(NativeClient.Failure.class, client::refresh);
        assertEquals(NativeClient.FailureKind.RATE_LIMIT, failure.kind);
        assertEquals(1200, failure.retrySeconds);
        assertEquals(0, state.lastRotation);
    }

    @Test
    void rotationSavesTimestampBeforeAuthenticating() throws Exception {
        var state = NativeCookies.capture(NativeCookiesTest.URL, "SID=old");
        ContentResponse rotated = response(200, "not-json");
        rotated.getHeaders().add("Set-Cookie", "__Secure-3PSIDTS=rotated; Secure; Domain=.google.com; Path=/");
        var saved = new ArrayList<Long>();
        var client = new NativeClient(
                transport(new ArrayList<>(), rotated, response(200, "{\"error\":\"USER_LOGGED_OUT\"}")), state,
                value -> saved.add(value.lastRotation));
        assertThrows(NativeClient.Failure.class, client::refresh);
        assertTrue(saved.getFirst() > 0);
    }

    @Test
    void rejectsTransportLookalikesAndKeepsUnknownStatesUndefined() {
        for (String url : List.of("http://transport.nest.com", "https://nest.com.evil.example",
                "https://secret@transport.nest.com", "https://transport.nest.com/unexpected",
                "https://transport.nest.com?secret")) {
            assertThrows(NativeClient.Failure.class, () -> NativeClient.validateTransport(url));
        }
        assertSame(UnDefType.UNDEF, NativeProtectHandler.alarm(null));
        assertSame(UnDefType.UNDEF, NativeProtectHandler.alarm(99));
        assertEquals("HEADS_UP_1", NativeProtectHandler.alarm(1).toString());
        assertEquals("HEADS_UP_2", NativeProtectHandler.alarm(2).toString());
        assertSame(UnDefType.UNDEF, NativeProtectHandler.battery(-1));
    }

    @Test
    void rejectedNestSessionCanBeReauthenticated() throws Exception {
        var state = NativeCookies.capture(NativeCookiesTest.URL, "SID=secret");
        state.lastRotation = System.currentTimeMillis();
        var requests = new ArrayList<Request>();
        var http = transport(requests, response(200, "{\"access_token\":\"google\"}"),
                response(200, "{\"jwt\":\"nest\"}"), response(200, "{\"userid\":\"u\",\"access_token\":\"old\"}"),
                response(401, "private failure"), response(200, "{\"access_token\":\"google\"}"),
                response(200, "{\"jwt\":\"nest\"}"), response(200, "{\"userid\":\"u\",\"access_token\":\"new\"}"),
                response(200, SNAPSHOT));
        var client = new NativeClient(http, state, ignored -> {
        });
        assertEquals(NativeClient.FailureKind.COMMUNICATION,
                assertThrows(NativeClient.Failure.class, client::refresh).kind);
        client.refresh();
        assertEquals(1, client.devices().size());
        verify(requests.getLast()).header("Authorization", "Basic new");
    }

    @Test
    void responseReceivedAfterCancellationCannotPersistCookies() throws Exception {
        var state = NativeCookies.capture(NativeCookiesTest.URL, "SID=secret");
        var http = mock(HttpClient.class);
        var request = mock(Request.class, RETURNS_SELF);
        var saved = new ArrayList<String>();
        var client = new NativeClient(http, state, value -> saved.add(NativeClient.GSON.toJson(value)));
        when(http.newRequest(anyString())).thenReturn(request);
        ContentResponse response = response(200, "private response");
        response.getHeaders().add("Set-Cookie", "__Secure-3PSIDTS=obsolete; Secure");
        when(request.send()).thenAnswer(invocation -> {
            client.cancel();
            return response;
        });
        assertThrows(NativeClient.Failure.class, client::refresh);
        assertTrue(saved.isEmpty());
        assertEquals(0, state.lastRotation);
    }

    private static HttpClient transport(List<Request> requests, ContentResponse... responses) throws Exception {
        HttpClient http = mock(HttpClient.class);
        var queue = new ArrayDeque<>(List.of(responses));
        when(http.newRequest(anyString())).thenAnswer(invocation -> {
            Request request = mock(Request.class, RETURNS_SELF);
            when(request.send()).thenReturn(queue.removeFirst());
            requests.add(request);
            return request;
        });
        return http;
    }

    private static ContentResponse response(int status, String body) {
        ContentResponse response = mock(ContentResponse.class);
        when(response.getStatus()).thenReturn(status);
        when(response.getContentAsString()).thenReturn(body);
        when(response.getHeaders()).thenReturn(new HttpFields());
        return response;
    }
}
