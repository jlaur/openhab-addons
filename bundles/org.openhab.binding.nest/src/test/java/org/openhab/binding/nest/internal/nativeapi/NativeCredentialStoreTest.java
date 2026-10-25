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

import java.security.GeneralSecurityException;
import java.util.Objects;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openhab.core.auth.client.oauth2.StorageCipher;
import org.openhab.core.storage.Storage;

/**
 * Tests that credentials never reach storage as plaintext and failures do not overwrite a previous session.
 *
 * @author Jacob Laursen - Initial contribution
 */
@ExtendWith(MockitoExtension.class)
@NonNullByDefault
class NativeCredentialStoreTest {

    @Test
    void savesEncryptedStateAndRestoresThroughTheSameCipher(@Mock Storage<String> storage, @Mock StorageCipher cipher)
            throws Exception {
        var state = NativeCookies.capture(NativeCookiesTest.URL, "SID=secret");
        when(cipher.getUniqueCipherId()).thenReturn("test-cipher");
        when(cipher.encrypt(anyString())).thenReturn("ciphertext");
        var store = new NativeCredentialStore(storage, cipher, "account");
        store.save(state);
        verify(storage).put("account", "test-cipher:ciphertext");
        when(storage.get("account")).thenReturn("test-cipher:ciphertext");
        when(cipher.decrypt("ciphertext")).thenReturn(NativeClient.GSON.toJson(state));
        assertEquals("secret", Objects.requireNonNull(store.load()).cookies.getFirst().value);
        store.remove();
        verify(storage).remove("account");
    }

    @Test
    void encryptionFailureDoesNotPersistPlaintext(@Mock Storage<String> storage, @Mock StorageCipher cipher)
            throws Exception {
        when(cipher.encrypt(anyString())).thenThrow(new GeneralSecurityException("private details"));
        var store = new NativeCredentialStore(storage, cipher, "account");
        var error = assertThrows(IllegalStateException.class,
                () -> store.save(NativeCookies.capture(NativeCookiesTest.URL, "SID=secret")));
        assertFalse(Objects.requireNonNull(error.getMessage()).contains("private"));
        verifyNoInteractions(storage);
    }

    @Test
    void missingStateIsDistinctFromCorruptState(@Mock Storage<String> storage, @Mock StorageCipher cipher) {
        var store = new NativeCredentialStore(storage, cipher, "account");
        assertNull(store.load());
        when(cipher.getUniqueCipherId()).thenReturn("cipher");
        when(storage.get("account")).thenReturn("unrecognized:secret");
        assertThrows(IllegalStateException.class, store::load);
    }
}
