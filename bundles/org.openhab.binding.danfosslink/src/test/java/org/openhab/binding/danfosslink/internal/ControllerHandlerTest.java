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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.openhab.binding.danfosslink.internal.DanfossLinkBindingConstants.*;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.binding.ThingHandlerCallback;
import org.openhab.core.thing.binding.builder.BridgeBuilder;

/**
 * Verifies that an in-flight response cannot publish after Bridge disposal.
 *
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
@Timeout(30)
class ControllerHandlerTest {
    @Test
    void disposalRejectsLateResponseAndReinitializationRecovers() throws Exception {
        var bridge = BridgeBuilder.create(CONTROLLER, "test").build();
        var identity = Identity.generate();
        var store = mock(PairingStore.class);
        when(store.load(anyString())).thenReturn(new PairingStore.Credentials(identity, identity.certificate));
        var client = mock(CloudClient.class);
        var session = mock(CloudClient.Session.class);
        when(client.open(any())).thenReturn(session);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var closed = new CountDownLatch(1);
        var online = new CountDownLatch(1);
        var state = new WorldState(Map.of(), null, "serial", "firmware");
        when(session.world()).thenAnswer(call -> {
            entered.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS));
            return state;
        });
        doAnswer(call -> {
            closed.countDown();
            return null;
        }).when(session).close();
        var callback = mock(ThingHandlerCallback.class);
        doAnswer(call -> {
            org.openhab.core.thing.ThingStatusInfo status = call.getArgument(1);
            if (status.getStatus() == ThingStatus.ONLINE) {
                online.countDown();
            }
            return null;
        }).when(callback).statusUpdated(eq(bridge), any());
        var handler = new ControllerHandler(bridge, store) {
            @Override
            CloudClient connect(Identity credentials, Operation operation) {
                return client;
            }
        };
        handler.setCallback(callback);
        try {
            handler.initialize();
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            handler.dispose();
            clearInvocations(callback);
            release.countDown();
            assertTrue(closed.await(10, TimeUnit.SECONDS));
            verify(callback, never()).statusUpdated(any(), any());
            verify(callback, never()).thingUpdated(any());
            handler.initialize();
            assertTrue(online.await(10, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            handler.dispose();
        }
    }

    @Test
    void pairingRejectsMalformedCodeBeforeConnecting() {
        var bridge = BridgeBuilder.create(CONTROLLER, "test").build();
        var handler = new ControllerHandler(bridge, mock(PairingStore.class));
        assertTrue(handler.pair("123").contains("nine digits"));
        assertTrue(handler.pair("12345678x").contains("nine digits"));
        assertTrue(handler.pair("012345678").contains("not initialized"));
    }
}
