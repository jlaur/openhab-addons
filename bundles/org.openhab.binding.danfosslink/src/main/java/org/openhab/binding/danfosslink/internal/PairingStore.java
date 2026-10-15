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

import java.io.IOException;
import java.util.Base64;

import org.bouncycastle.cert.X509CertificateHolder;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.storage.Storage;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Bridge identities and controller pins in openHAB storage, following the HomeKit key-store pattern.
 *
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
public final class PairingStore {
    private final Storage<String> storage;
    private final Gson gson = new Gson();

    public PairingStore(Storage<String> storage) {
        this.storage = storage;
    }

    public synchronized Credentials load(String uid) throws Exception {
        String value = storage.get(uid);
        if (value == null) {
            Identity identity = Identity.generate();
            Credentials credentials = new Credentials(identity, null);
            save(uid, credentials);
            return credentials;
        }
        try {
            var object = JsonParser.parseString(value).getAsJsonObject();
            Identity identity = Identity.decode(object.get("identity").getAsString());
            X509CertificateHolder controller = object.has("controller")
                    ? new X509CertificateHolder(Base64.getDecoder().decode(object.get("controller").getAsString()))
                    : null;
            return new Credentials(identity, controller);
        } catch (RuntimeException e) {
            throw new IOException("Invalid persisted pairing data", e);
        }
    }

    public synchronized void save(String uid, Credentials credentials) throws IOException {
        var object = new JsonObject();
        object.addProperty("identity", credentials.identity.encode());
        X509CertificateHolder controller = credentials.controller;
        if (controller != null) {
            object.addProperty("controller", Base64.getEncoder().encodeToString(controller.getEncoded()));
        }
        storage.put(uid, gson.toJson(object));
    }

    public static final class Credentials {
        public final Identity identity;
        public final @Nullable X509CertificateHolder controller;

        public Credentials(Identity identity, @Nullable X509CertificateHolder controller) {
            this.identity = identity;
            this.controller = controller;
        }
    }
}
