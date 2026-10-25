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

import java.net.HttpCookie;
import java.net.URI;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.nest.internal.nativeapi.dto.NativeDtos.Cookie;
import org.openhab.binding.nest.internal.nativeapi.dto.NativeDtos.Credentials;

/**
 * Cookie handling restricted to the Google endpoints used for Nest authentication.
 *
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
final class NativeCookies {
    private NativeCookies() {
    }

    static Credentials capture(String url, String header) {
        validateTokenUrl(url);
        if (header.isBlank() || header.contains("\r") || header.contains("\n")) {
            throw new IllegalArgumentException("Supply a single Cookie request-header value");
        }
        Credentials state = new Credentials();
        state.issueTokenUrl = url;
        for (String pair : header.split(";")) {
            int equals = pair.indexOf('=');
            if (equals <= 0) {
                throw new IllegalArgumentException("Invalid Cookie request-header value");
            }
            Cookie cookie = new Cookie();
            cookie.name = pair.substring(0, equals).trim();
            cookie.value = pair.substring(equals + 1).trim();
            // A capture has no domain/path/expiry metadata. Scope it to Google only.
            new HttpCookie(cookie.name, cookie.value);
            state.cookies.add(cookie);
        }
        return state;
    }

    static void validateTokenUrl(String value) {
        try {
            URI uri = URI.create(value);
            if (!"https".equals(uri.getScheme()) || !"accounts.google.com".equals(uri.getHost()) || uri.getPort() != -1
                    || uri.getUserInfo() != null || uri.getFragment() != null
                    || !"/o/oauth2/iframerpc".equals(uri.getPath()) || uri.getQuery() == null
                    || !List.of(uri.getQuery().split("&")).contains("action=issueToken")) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid Google issueToken endpoint");
        }
    }

    static String header(Credentials state, URI uri, long now) {
        return state.cookies.stream().filter(c -> c.expiresAt > now)
                .filter(c -> !c.secure || "https".equals(uri.getScheme()))
                .filter(c -> c.hostOnly ? c.domain.equals(uri.getHost())
                        : c.domain.equals(uri.getHost()) || uri.getHost().endsWith("." + c.domain))
                .filter(c -> uri.getPath().equals(c.path) || (uri.getPath().startsWith(c.path)
                        && (c.path.endsWith("/") || uri.getPath().charAt(c.path.length()) == '/')))
                .sorted(Comparator.comparingInt((Cookie c) -> c.path.length()).reversed())
                .map(c -> c.name + "=" + c.value).collect(Collectors.joining("; "));
    }

    static boolean merge(Credentials state, URI source, List<String> headers, long now) {
        boolean timestamp = false;
        for (String header : headers) {
            for (HttpCookie parsed : HttpCookie.parse(header)) {
                Cookie cookie = new Cookie();
                cookie.name = parsed.getName();
                cookie.value = parsed.getValue();
                String domain = parsed.getDomain();
                cookie.hostOnly = domain == null;
                cookie.domain = domain == null ? "accounts.google.com" : domain.replaceFirst("^\\.", "");
                if (!"google.com".equals(cookie.domain) && !"accounts.google.com".equals(cookie.domain)) {
                    continue;
                }
                String path = parsed.getPath();
                int slash = source.getPath().lastIndexOf('/');
                String defaultPath = slash <= 0 ? "/" : source.getPath().substring(0, slash);
                cookie.path = path == null || !path.startsWith("/") ? defaultPath : path;
                cookie.secure = parsed.getSecure();
                long age = parsed.getMaxAge();
                cookie.expiresAt = age < 0 ? Long.MAX_VALUE : now + Math.min(age, (Long.MAX_VALUE - now) / 1000) * 1000;
                state.cookies.removeIf(c -> c.name.equals(cookie.name)
                        && (c.domain.equals(cookie.domain) || c.hostOnly && c.domain.equals("accounts.google.com"))
                        && c.path.equals(cookie.path));
                if (age != 0) {
                    state.cookies.add(cookie);
                    timestamp |= "__Secure-1PSIDTS".equals(cookie.name) || "__Secure-3PSIDTS".equals(cookie.name);
                }
            }
        }
        state.cookies.removeIf(c -> c.expiresAt <= now);
        return timestamp;
    }
}
