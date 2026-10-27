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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.Request;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openhab.core.io.net.http.HttpClientFactory;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.ThingStatusInfo;
import org.openhab.core.thing.binding.ThingHandlerCallback;
import org.openhab.core.thing.binding.builder.BridgeBuilder;

/**
 * Tests unauthenticated action availability and cancellation without time-based sleeps.
 *
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
class NativeAccountHandlerTest {
    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    @Timeout(20)
    void modeCommandWakesSubscriptionAndPublishesOnlyObservedState(boolean pathlight) throws Exception {
        var bridge = BridgeBuilder.create(NativeThingHandlerFactory.ACCOUNT, "commands").build();
        var store = mock(NativeCredentialStore.class);
        var credentials = NativeCookies.capture(NativeCookiesTest.URL, "SID=s");
        credentials.lastRotation = System.currentTimeMillis();
        when(store.load()).thenReturn(credentials);
        var factory = mock(HttpClientFactory.class);
        var http = mock(HttpClient.class);
        when(factory.createHttpClient("nest-native")).thenReturn(http);
        var polling = new CountDownLatch(1);
        var queued = new CountDownLatch(1);
        var observed = new CountDownLatch(1);
        var writes = new AtomicInteger();
        var handler = new NativeAccountHandler(bridge, factory, store);
        when(http.newRequest(anyString())).thenAnswer(invocation -> {
            String url = invocation.getArgument(0);
            Request request = mock(Request.class, RETURNS_SELF);
            var aborted = new CountDownLatch(1);
            var cause = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            when(request.abort(any())).thenAnswer(abort -> {
                cause.set(abort.getArgument(0));
                aborted.countDown();
                return true;
            });
            when(request.send()).thenAnswer(send -> {
                String body;
                if (url.contains("iframerpc")) {
                    body = "{\"access_token\":\"google\"}";
                } else if (url.endsWith("issue_jwt")) {
                    body = "{\"jwt\":\"nest\"}";
                } else if (url.endsWith("/session")) {
                    body = "{\"userid\":\"u\",\"access_token\":\"session\"}";
                } else if (url.endsWith("/v6/subscribe")) {
                    polling.countDown();
                    assertTrue(aborted.await(10, TimeUnit.SECONDS));
                    assertTrue(queued.await(10, TimeUnit.SECONDS));
                    throw new ExecutionException(cause.get());
                } else if (url.endsWith(pathlight ? "/v2/put/topaz.home" : "/v2/put/structure.home")) {
                    assertEquals(false, java.util.Objects.requireNonNull(handler.structures().get("home")).away);
                    assertEquals(NativePathlightMode.OFF, NativePathlightMode
                            .observed(java.util.Objects.requireNonNull(handler.devices().get("home"))));
                    writes.incrementAndGet();
                    body = "{}";
                } else if (url.endsWith("/app_launch")) {
                    body = """
                            {"service_urls":{"urls":{"transport_url":"https://transport.nest.com"}},
                             "updated_buckets":[{"object_key":"structure.home","object_revision":1,
                             "object_timestamp":10,"value":{"away":%s}},
                             {"object_key":"topaz.home","object_revision":1,"object_timestamp":10,
                             "value":{"night_light_enable":%s,"night_light_continuous":false,"wired_or_battery":0}}]}
                            """.formatted(!pathlight && writes.get() > 0, pathlight && writes.get() > 0);
                } else {
                    throw new AssertionError("Unexpected endpoint");
                }
                var response = mock(org.eclipse.jetty.client.api.ContentResponse.class);
                when(response.getStatus()).thenReturn(200);
                when(response.getHeaders()).thenReturn(new org.eclipse.jetty.http.HttpFields());
                when(response.getContentAsString()).thenReturn(body);
                return response;
            });
            return request;
        });
        var callback = mock(ThingHandlerCallback.class);
        doAnswer(invocation -> {
            ThingStatusInfo status = invocation.getArgument(1);
            bridge.setStatusInfo(status);
            var structure = handler.structures().get("home");
            var protect = handler.devices().get("home");
            if (pathlight ? protect != null && NativePathlightMode.observed(protect) == NativePathlightMode.ON
                    : structure != null && Boolean.TRUE.equals(structure.away)) {
                observed.countDown();
            }
            return null;
        }).when(callback).statusUpdated(eq(bridge), any());
        handler.setCallback(callback);
        try {
            handler.initialize();
            assertTrue(polling.await(10, TimeUnit.SECONDS));
            assertFalse(handler.setAway("unknown", true));
            assertFalse(handler.setPathlight("unknown", NativePathlightMode.ON));
            assertTrue(
                    pathlight ? handler.setPathlight("home", NativePathlightMode.ON) : handler.setAway("home", true));
            assertFalse(handler.setPathlightBrightness("unknown", NativePathlightBrightness.HIGH));
            assertFalse(handler.setNightlyPromise("unknown", true));
            assertFalse(handler.setHomeAwayInput("unknown", true));
            if (pathlight) {
                assertTrue(handler.setHomeAwayInput("home", false));
                assertTrue(handler.setHomeAwayInput("home", true));
                assertTrue(handler.setNightlyPromise("home", false));
                assertTrue(handler.setNightlyPromise("home", true));
                assertTrue(handler.setPathlightBrightness("home", NativePathlightBrightness.LOW));
                assertTrue(handler.setPathlightBrightness("home", NativePathlightBrightness.HIGH));
            }
            queued.countDown();
            assertTrue(observed.await(10, TimeUnit.SECONDS));
            assertEquals(pathlight ? 4 : 1, writes.get());
        } finally {
            handler.dispose();
        }
        assertFalse(handler.setNightlyPromise("home", true));
        assertFalse(handler.setHomeAwayInput("home", true));
        assertFalse(handler.setAway("home", false));
        assertFalse(handler.setPathlight("home", NativePathlightMode.OFF));
        assertFalse(handler.setPathlightBrightness("home", NativePathlightBrightness.LOW));
    }

    @Test
    @Timeout(15)
    void repeatedInitializationStopsThePreviousRun() throws Exception {
        var bridge = BridgeBuilder.create(NativeThingHandlerFactory.ACCOUNT, "repeat").build();
        var store = mock(NativeCredentialStore.class);
        var factory = mock(HttpClientFactory.class);
        var first = new CountDownLatch(1);
        var second = new CountDownLatch(1);
        var pending = new AtomicInteger();
        var callback = mock(ThingHandlerCallback.class);
        doAnswer(invocation -> {
            ThingStatusInfo status = invocation.getArgument(1);
            if (status.getStatusDetail() == ThingStatusDetail.AUTHORIZATION_PENDING) {
                if (pending.incrementAndGet() == 1) {
                    first.countDown();
                } else {
                    second.countDown();
                }
            }
            return null;
        }).when(callback).statusUpdated(eq(bridge), any());
        var handler = new NativeAccountHandler(bridge, factory, store);
        handler.setCallback(callback);
        try {
            handler.initialize();
            assertTrue(first.await(10, TimeUnit.SECONDS));
            handler.initialize();
            assertTrue(second.await(10, TimeUnit.SECONDS));
            verify(store, times(2)).load();
            verifyNoInteractions(factory);
        } finally {
            handler.dispose();
        }
    }

    @Test
    @Timeout(15)
    void authenticationCanStartWhileOfflineAndDisposalAbortsTheRequest() throws Exception {
        var bridge = BridgeBuilder.create(NativeThingHandlerFactory.ACCOUNT, "test").build();
        var store = mock(NativeCredentialStore.class);
        var factory = mock(HttpClientFactory.class);
        var http = mock(HttpClient.class);
        var request = mock(Request.class, RETURNS_SELF);
        var waitingForCredentials = new CountDownLatch(1);
        var requestStarted = new CountDownLatch(1);
        var requestAborted = new CountDownLatch(1);
        var transportStopped = new CountDownLatch(1);
        var removed = new CountDownLatch(1);
        doAnswer(invocation -> {
            removed.countDown();
            return null;
        }).when(store).remove();
        var onlineUpdates = new AtomicInteger();
        when(factory.createHttpClient("nest-native")).thenReturn(http);
        when(http.newRequest(anyString())).thenReturn(request);
        when(request.send()).thenAnswer(invocation -> {
            requestStarted.countDown();
            assertTrue(requestAborted.await(10, TimeUnit.SECONDS));
            throw new ExecutionException(new IllegalStateException("aborted"));
        });
        when(request.abort(any())).thenAnswer(invocation -> {
            requestAborted.countDown();
            return true;
        });
        doAnswer(invocation -> {
            transportStopped.countDown();
            return null;
        }).when(http).stop();
        var callback = mock(ThingHandlerCallback.class);
        doAnswer(invocation -> {
            ThingStatusInfo status = invocation.getArgument(1);
            if (status.getStatusDetail() == ThingStatusDetail.AUTHORIZATION_PENDING) {
                waitingForCredentials.countDown();
            }
            if (status.getStatus() == ThingStatus.ONLINE) {
                onlineUpdates.incrementAndGet();
            }
            return null;
        }).when(callback).statusUpdated(eq(bridge), any());
        var handler = new NativeAccountHandler(bridge, factory, store);
        handler.setCallback(callback);
        var actions = new NativeAccountActions();
        actions.setThingHandler(handler);
        try {
            handler.initialize();
            assertTrue(waitingForCredentials.await(10, TimeUnit.SECONDS));
            assertFalse(actions.authenticate("https://evil.example", "SID=secret"));
            assertTrue(actions.authenticate(NativeCookiesTest.URL, "SID=secret"));
            assertTrue(requestStarted.await(10, TimeUnit.SECONDS));
            handler.dispose();
            assertTrue(transportStopped.await(10, TimeUnit.SECONDS));
            assertEquals(0, onlineUpdates.get());
            verify(store, never()).remove();
            assertFalse(actions.authenticate(NativeCookiesTest.URL, "SID=secret"));
            handler.handleRemoval();
            assertTrue(removed.await(10, TimeUnit.SECONDS));
            verify(store).remove();
            actions.setThingHandler(null);
            assertFalse(actions.authenticate(NativeCookiesTest.URL, "SID=secret"));
        } finally {
            requestAborted.countDown();
            handler.dispose();
        }
    }
}
