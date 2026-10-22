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

import static org.junit.jupiter.api.Assertions.*;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.binding.danfosslink.internal.WorldState;
import org.openhab.binding.danfosslink.internal.protocol.dto.ModeState;
import org.openhab.binding.danfosslink.internal.protocol.dto.ModeType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.types.UnDefType;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Staged selections, Living-zone grouping, request capture and fresh eligibility checks.
 *
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
class HomeSelectionTest {
    private JsonObject world() {
        return JsonParser.parseString("""
                {"allRooms":[
                {"id":0,"zone":"None","regulation":{"type":"Schedule"}},
                {"id":1,"zone":"Living","regulation":{"type":"Schedule"}},
                {"id":2,"zone":"Living","regulation":{"type":"Schedule"}}],
                "modes":[{"type":"AtHome","state":"Inactive"}]}
                """).getAsJsonObject();
    }

    @Test
    void zoneAndMembersMirrorOneSelectionAndExpandWithoutDuplicates() throws Exception {
        var selection = new HomeSelection();
        var snapshot = WorldState.parse(world());
        assertEquals(OnOffType.OFF, selection.state(null, snapshot));
        selection.select(1, OnOffType.ON, snapshot);
        assertEquals(OnOffType.ON, selection.state(null, snapshot));
        assertEquals(OnOffType.ON, selection.state(2, snapshot));
        assertEquals(OnOffType.OFF, selection.state(0, snapshot));
        selection.select(0, OnOffType.ON, snapshot);
        assertEquals(java.util.List.of(0, 1, 2), selection.request(OnOffType.ON).body(snapshot).rooms);
        selection.select(2, OnOffType.OFF, snapshot);
        assertEquals(OnOffType.OFF, selection.state(1, snapshot));
        assertEquals(java.util.List.of(0), selection.request(OnOffType.ON).body(snapshot).rooms);
    }

    @Test
    void requestsCaptureIntentAndCancellationRetainsStaging() throws Exception {
        var selection = new HomeSelection();
        var snapshot = WorldState.parse(world());
        selection.select(0, OnOffType.ON, snapshot);
        var request = selection.request(OnOffType.ON);
        selection.select(0, OnOffType.OFF, snapshot);
        selection.select(null, OnOffType.ON, snapshot);
        assertEquals(java.util.List.of(0), request.body(snapshot).rooms);
        var cancel = selection.request(OnOffType.OFF).body(snapshot);
        assertEquals(ModeType.AT_HOME, cancel.type);
        assertEquals(ModeState.INACTIVE, cancel.state);
        assertNull(cancel.rooms);
        assertEquals(java.util.List.of(1, 2), selection.request(OnOffType.ON).body(snapshot).rooms);
    }

    @Test
    void emptyManualMissingAndUnknownSelectionsAreRejected() throws Exception {
        var selection = new HomeSelection();
        var json = world();
        var snapshot = WorldState.parse(json);
        assertThrows(IllegalArgumentException.class, () -> selection.request(OnOffType.ON).body(snapshot));
        selection.select(0, OnOffType.ON, snapshot);
        var request = selection.request(OnOffType.ON);
        for (String type : new String[] { "Manual", "Unknown" }) {
            json.getAsJsonArray("allRooms").get(0).getAsJsonObject().getAsJsonObject("regulation").addProperty("type",
                    type);
            assertThrows(IllegalArgumentException.class, () -> request.body(WorldState.parse(json)));
        }
        json.getAsJsonArray("allRooms").remove(0);
        assertThrows(IllegalArgumentException.class, () -> request.body(WorldState.parse(json)));
        assertEquals(UnDefType.UNDEF, selection.state(0, WorldState.parse(json)));
        assertThrows(IllegalArgumentException.class, () -> selection.select(0, OnOffType.ON, WorldState.parse(json)));
        selection.select(0, OnOffType.OFF, WorldState.parse(json));
        selection.select(null, OnOffType.ON, WorldState.parse(json));
        assertEquals(java.util.List.of(1, 2), selection.request(OnOffType.ON).body(WorldState.parse(json)).rooms);
        json.remove("modes");
        assertThrows(IllegalArgumentException.class,
                () -> selection.request(OnOffType.OFF).body(WorldState.parse(json)));
    }

    @Test
    void freshZoneMembershipIsExpandedAndExplicitReselectionResolvesMovedRoom() throws Exception {
        var selection = new HomeSelection();
        var json = world();
        selection.select(0, OnOffType.ON, WorldState.parse(json));
        var previous = selection.request(OnOffType.ON);
        json.getAsJsonArray("allRooms").get(0).getAsJsonObject().addProperty("zone", "Living");
        var fresh = WorldState.parse(json);
        assertThrows(IllegalArgumentException.class, () -> previous.body(fresh));
        selection.select(null, OnOffType.ON, fresh);
        assertEquals(java.util.List.of(0, 1, 2), selection.request(OnOffType.ON).body(fresh).rooms);
        var zone = selection.request(OnOffType.ON);
        json.getAsJsonArray("allRooms")
                .add(JsonParser.parseString("{\"id\":3,\"zone\":\"Living\",\"regulation\":{\"type\":\"Schedule\"}}"));
        assertEquals(java.util.List.of(0, 1, 2, 3), zone.body(WorldState.parse(json)).rooms);
        json.getAsJsonArray("allRooms").get(3).getAsJsonObject().getAsJsonObject("regulation").addProperty("type",
                "Manual");
        assertThrows(IllegalArgumentException.class, () -> zone.body(WorldState.parse(json)));
    }

    @Test
    void reportedOverrideDoesNotRepresentStagedSelection() throws Exception {
        var selection = new HomeSelection();
        var json = world();
        json.getAsJsonArray("modes").get(0).getAsJsonObject().addProperty("state", "Active");
        var snapshot = WorldState.parse(json);
        assertEquals(OnOffType.ON, HomeSelection.reported(snapshot.home()));
        assertEquals(OnOffType.OFF, selection.state(0, snapshot));
        assertEquals(UnDefType.UNDEF, HomeSelection.reported(null));
    }
}
