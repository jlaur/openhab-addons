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

import java.net.URI;
import java.util.List;
import java.util.Objects;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.binding.nest.internal.nativeapi.dto.NativeDtos;

/**
 * Tests credential endpoint validation and persisted cookie semantics.
 *
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
class NativeCookiesTest {
    static final String URL = "https://accounts.google.com/o/oauth2/iframerpc?action=issueToken";
    private static final URI GOOGLE = URI.create(URL);

    @Test
    void rejectsUntrustedEndpointsAndHeaderInjection() {
        for (String url : List.of("http://accounts.google.com/o/oauth2/iframerpc?action=issueToken",
                URL.replace("accounts.google.com", "accounts.google.com.evil.example"),
                URL.replace("accounts.google.com", "secret@accounts.google.com"), URL + "#fragment")) {
            assertThrows(IllegalArgumentException.class, () -> NativeCookies.capture(url, "SID=secret"));
        }
        assertThrows(IllegalArgumentException.class, () -> NativeCookies.capture(URL, "SID=x\r\nInjected: x"));
    }

    @Test
    void retainsEqualsAndLimitsCapturedCookiesToGoogle() {
        var state = NativeCookies.capture(URL, "SID=a=b; HSID=other");
        assertEquals("SID=a=b; HSID=other", NativeCookies.header(state, GOOGLE, 1000));
        assertEquals("", NativeCookies.header(state, URI.create("https://home.nest.com/"), 1000));
    }

    @Test
    void rotationReplacesCapturedCookieAndPersistsAbsoluteExpiry() {
        var state = NativeCookies.capture(URL, "SID=old; HSID=keep");
        NativeCookies.merge(state, GOOGLE, List.of("SID=new; Domain=.google.com; Path=/; Secure; Max-Age=10"), 1000);
        String stored = NativeClient.GSON.toJson(state);
        var restored = Objects.requireNonNull(NativeClient.GSON.fromJson(stored, NativeDtos.Credentials.class));
        assertEquals("HSID=keep; SID=new", NativeCookies.header(restored, GOOGLE, 2000));
        assertEquals("HSID=keep", NativeCookies.header(restored, GOOGLE, 11000));
        assertEquals(2, restored.cookies.size());
    }

    @Test
    void deletionPathAndDomainAreRespected() {
        var state = NativeCookies.capture(URL, "SID=old");
        NativeCookies.merge(state, GOOGLE,
                List.of("SID=deleted; Max-Age=0; Path=/", "HSID=path; Path=/other; Secure",
                        "EVIL=value; Domain=example.com", "EXPIRED=value; Expires=Thu, 01 Jan 1970 00:00:00 GMT"),
                System.currentTimeMillis());
        assertEquals("", NativeCookies.header(state, GOOGLE, System.currentTimeMillis()));
        assertEquals("HSID=path", NativeCookies.header(state, URI.create("https://accounts.google.com/other/x"),
                System.currentTimeMillis()));
        assertEquals("", NativeCookies.header(state, URI.create("https://accounts.google.com/otherwise"),
                System.currentTimeMillis()));
    }

    @Test
    void missingCookiePathDefaultsToTheRequestDirectory() {
        var state = NativeCookies.capture(URL, "SID=keep");
        NativeCookies.merge(state, GOOGLE, List.of("PATH_COOKIE=value; Secure"), 1000);
        assertEquals("PATH_COOKIE=value; SID=keep", NativeCookies.header(state, GOOGLE, 1000));
        assertEquals("SID=keep",
                NativeCookies.header(state, URI.create("https://accounts.google.com/RotateCookies"), 1000));
    }
}
