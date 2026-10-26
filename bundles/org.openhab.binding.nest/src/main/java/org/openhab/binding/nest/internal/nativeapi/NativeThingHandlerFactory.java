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

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.auth.client.oauth2.StorageCipher;
import org.openhab.core.io.net.http.HttpClientFactory;
import org.openhab.core.storage.StorageService;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.binding.BaseThingHandlerFactory;
import org.openhab.core.thing.binding.ThingHandler;
import org.openhab.core.thing.binding.ThingHandlerFactory;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

/**
 * Creates handlers for native accounts, Protects, and structures independently of the SDM implementation.
 *
 * @author Jacob Laursen - Initial contribution
 */
@Component(service = ThingHandlerFactory.class, configurationPid = "binding.nest")
@NonNullByDefault
public class NativeThingHandlerFactory extends BaseThingHandlerFactory {
    static final ThingTypeUID ACCOUNT = new ThingTypeUID("nest", "native_account");
    static final ThingTypeUID PROTECT = new ThingTypeUID("nest", "native_protect");
    static final ThingTypeUID STRUCTURE = new ThingTypeUID("nest", "native_structure");
    private final HttpClientFactory httpFactory;
    private final StorageService storage;
    private final StorageCipher cipher;

    @Activate
    public NativeThingHandlerFactory(@Reference HttpClientFactory httpFactory, @Reference StorageService storage,
            @Reference StorageCipher cipher) {
        this.httpFactory = httpFactory;
        this.storage = storage;
        this.cipher = cipher;
    }

    @Override
    public boolean supportsThingType(ThingTypeUID type) {
        return ACCOUNT.equals(type) || PROTECT.equals(type) || STRUCTURE.equals(type);
    }

    @Override
    protected @Nullable ThingHandler createHandler(Thing thing) {
        if (ACCOUNT.equals(thing.getThingTypeUID()) && thing instanceof Bridge bridge) {
            return new NativeAccountHandler(bridge, httpFactory, new NativeCredentialStore(
                    storage.getStorage("nest.native.accounts"), cipher, thing.getUID().toString()));
        }
        if (STRUCTURE.equals(thing.getThingTypeUID())) {
            return new NativeStructureHandler(thing);
        }
        return PROTECT.equals(thing.getThingTypeUID()) ? new NativeProtectHandler(thing) : null;
    }
}
