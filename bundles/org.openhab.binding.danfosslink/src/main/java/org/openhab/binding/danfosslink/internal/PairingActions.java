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

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.automation.annotation.ActionInput;
import org.openhab.core.automation.annotation.ActionOutput;
import org.openhab.core.automation.annotation.RuleAction;
import org.openhab.core.thing.binding.ThingActions;
import org.openhab.core.thing.binding.ThingActionsScope;
import org.openhab.core.thing.binding.ThingHandler;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ServiceScope;

/**
 * Starts cloud pairing without blocking the rule or UI action thread.
 *
 * @author Jacob Laursen - Initial contribution
 */
@Component(scope = ServiceScope.PROTOTYPE, service = PairingActions.class)
@ThingActionsScope(name = "danfosslink")
@NonNullByDefault
public class PairingActions implements ThingActions {
    private volatile @Nullable ControllerHandler handler;

    @Override
    public @Nullable ThingHandler getThingHandler() {
        return handler;
    }

    @Override
    public void setThingHandler(@Nullable ThingHandler handler) {
        this.handler = handler instanceof ControllerHandler controller ? controller : null;
    }

    @RuleAction(label = "Pair Controller", description = "Pair using the nine-digit code displayed by the controller")
    public @ActionOutput(type = "java.lang.String", label = "Result") String pair(
            @ActionInput(name = "pin", label = "Pairing Code", description = "Nine digits, including any leading zeros") String pin) {
        ControllerHandler controller = handler;
        return controller == null ? "Controller is unavailable." : controller.pair(pin);
    }

    public static String pair(ThingActions actions, String pin) {
        if (actions instanceof PairingActions pairing) {
            return pairing.pair(pin);
        }
        throw new IllegalArgumentException("Not Danfoss Link actions");
    }
}
