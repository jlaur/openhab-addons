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

import java.security.GeneralSecurityException;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.nest.internal.nativeapi.dto.NativeDtos.Credentials;
import org.openhab.core.auth.client.oauth2.StorageCipher;
import org.openhab.core.storage.Storage;

/**
 * Encrypts account credentials using the core credential cipher before handing them to persistent storage.
 *
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
final class NativeCredentialStore {
    private final Storage<String> storage;
    private final StorageCipher cipher;
    private final String key;

    NativeCredentialStore(Storage<String> storage, StorageCipher cipher, String key) {
        this.storage = storage;
        this.cipher = cipher;
        this.key = key;
    }

    @Nullable
    Credentials load() {
        String stored = storage.get(key);
        if (stored == null) {
            return null;
        }
        String prefix = cipher.getUniqueCipherId() + ":";
        try {
            if (!stored.startsWith(prefix)) {
                throw new GeneralSecurityException();
            }
            String plain = cipher.decrypt(stored.substring(prefix.length()));
            Credentials credentials = NativeClient.GSON.fromJson(plain, Credentials.class);
            if (credentials == null || credentials.version != 1 || credentials.cookies == null) {
                throw new GeneralSecurityException();
            }
            NativeCookies.validateTokenUrl(credentials.issueTokenUrl);
            return credentials;
        } catch (GeneralSecurityException | RuntimeException e) {
            throw new IllegalStateException("Stored authentication state cannot be read; use the authenticate action");
        }
    }

    void save(Credentials credentials) {
        try {
            String encrypted = cipher.encrypt(NativeClient.GSON.toJson(credentials));
            if (encrypted == null) {
                throw new GeneralSecurityException();
            }
            storage.put(key, cipher.getUniqueCipherId() + ":" + encrypted);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Authentication state could not be encrypted");
        }
    }

    void remove() {
        storage.remove(key);
    }
}
