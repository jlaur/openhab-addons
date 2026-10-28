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
package org.openhab.binding.danfosslink.internal.handler;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.library.types.OnOffType;

/**
 * Pending writes and their replacement rules, guarded by the controller's lifecycle lock.
 * Draining detaches the batch so later commands cannot replace a write already being processed.
 *
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
final class PendingCommands {
    private final Map<PendingCommand.Key, PendingCommand> commands = new LinkedHashMap<>();

    void add(PendingCommand command) {
        if (command instanceof PendingCommand.Vacation vacation && vacation.request().command() == OnOffType.OFF) {
            commands.keySet().removeIf(key -> key.kind() == PendingCommand.Kind.VACATION);
        }
        if (command instanceof PendingCommand.Schedule later
                && commands.get(command.key()) instanceof PendingCommand.Schedule earlier) {
            command = earlier.merge(later);
        }
        // Replacements move to the end of their kind, preserving ordering between distinct date edits/channels.
        commands.remove(command.key());
        commands.put(command.key(), command);
    }

    boolean contains(PendingCommand.Kind kind) {
        return commands.keySet().stream().anyMatch(key -> key.kind() == kind);
    }

    boolean isEmpty() {
        return commands.isEmpty();
    }

    List<PendingCommand> drain() {
        var batch = commands.values().stream().sorted(Comparator.comparing(command -> command.key().kind())).toList();
        commands.clear();
        return batch;
    }

    void clear() {
        commands.clear();
    }
}
