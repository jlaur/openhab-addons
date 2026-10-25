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
