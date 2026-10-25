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
import org.openhab.core.automation.annotation.ActionInput;
import org.openhab.core.automation.annotation.RuleAction;
import org.openhab.core.thing.binding.ThingActions;
import org.openhab.core.thing.binding.ThingActionsScope;
import org.openhab.core.thing.binding.ThingHandler;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ServiceScope;

/**
 * Supplies browser credentials without retaining the initial capture in Thing configuration.
 *
 * @author Jacob Laursen - Initial contribution
 */
@Component(scope = ServiceScope.PROTOTYPE, service = NativeAccountActions.class)
@ThingActionsScope(name = "nest")
@NonNullByDefault
public class NativeAccountActions implements ThingActions {
    private volatile @Nullable NativeAccountHandler handler;

    @Override
    public void setThingHandler(@Nullable ThingHandler handler) {
        this.handler = handler instanceof NativeAccountHandler account ? account : null;
    }

    @Override
    public @Nullable ThingHandler getThingHandler() {
        return handler;
    }

    @RuleAction(label = "Authenticate", description = "Submit browser credentials to the native Nest account. True means accepted, not authenticated.")
    public boolean authenticate(
            @ActionInput(name = "issueTokenUrl", label = "Google issueToken request URL", required = true) String issueTokenUrl,
            @ActionInput(name = "cookieHeader", label = "Google Cookie request-header value", required = true) String cookieHeader) {
        NativeAccountHandler account = handler;
        return account != null && account.authenticate(issueTokenUrl, cookieHeader);
    }

    public static boolean authenticate(ThingActions actions, String issueTokenUrl, String cookieHeader) {
        return actions instanceof NativeAccountActions nativeActions
                && nativeActions.authenticate(issueTokenUrl, cookieHeader);
    }
}
