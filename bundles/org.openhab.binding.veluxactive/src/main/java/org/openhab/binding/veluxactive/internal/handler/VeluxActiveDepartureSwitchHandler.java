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
package org.openhab.binding.veluxactive.internal.handler;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.veluxactive.internal.api.dto.HomeStatus;
import org.openhab.binding.veluxactive.internal.api.dto.Module;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;

/**
 * Reports the physical NXD departure switch's battery and connectivity diagnostics.
 *
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
public class VeluxActiveDepartureSwitchHandler extends VeluxActiveBatteryModuleHandler {
    public VeluxActiveDepartureSwitchHandler(Thing thing) {
        super(thing);
    }

    @Override
    synchronized void update(ModuleRequestContext target, HomeStatus status) {
        if (!isRequestContextCurrent(target)) {
            return;
        }
        Module module = status.module(target.moduleId());
        if (module != null && !checkGateway(target, module)) {
            return;
        }
        if (status.error(target.moduleId()) != null || module != null && Boolean.FALSE.equals(module.reachable)) {
            failed(target, "Departure switch is unreachable.");
        } else if (module == null) {
            clearState();
            updateStatus(ThingStatus.UNKNOWN, ThingStatusDetail.NONE, "Switch is absent from the latest home status.");
        } else {
            updateDiagnostics(module);
            updateStatus(ThingStatus.ONLINE);
        }
    }
}
