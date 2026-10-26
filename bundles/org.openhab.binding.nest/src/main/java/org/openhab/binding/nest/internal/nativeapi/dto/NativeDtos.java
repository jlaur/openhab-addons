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
package org.openhab.binding.nest.internal.nativeapi.dto;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jdt.annotation.NonNull;

import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;

/**
 * Wire and storage DTOs. Boxed fields distinguish missing values from valid zero and false values.
 *
 * @author Jacob Laursen - Initial contribution
 */
public final class NativeDtos {
    private NativeDtos() {
    }

    public static class Credentials {
        public int version = 1;
        public String issueTokenUrl = "";
        public List<Cookie> cookies = new ArrayList<>();
        public long lastRotation;
    }

    public static class Cookie {
        public String name = "";
        public String value = "";
        public String domain = "accounts.google.com";
        public String path = "/";
        public boolean hostOnly = true;
        public boolean secure = true;
        public long expiresAt = Long.MAX_VALUE;
    }

    public static class GoogleToken {
        @SerializedName("access_token")
        public String accessToken;
        @SerializedName("expires_in")
        public Long expiresIn;
        public String error;
    }

    public static class NestToken {
        public String jwt;
        public Claims claims;
    }

    public static class Claims {
        public String expirationTime;
    }

    public static class Session {
        public String userid;
        @SerializedName("access_token")
        public String accessToken;
    }

    public static class LaunchRequest {
        @SerializedName("known_bucket_types")
        public List<String> types = List.of("topaz", "structure", "where", "user");
        @SerializedName("known_bucket_versions")
        public List<String> versions = List.of();
    }

    public static class LaunchResponse {
        @SerializedName("updated_buckets")
        public List<Bucket> buckets;
        @SerializedName("service_urls")
        public Services services;
    }

    public static class Services {
        public Urls urls;
    }

    public static class Urls {
        @SerializedName("transport_url")
        public String transport;
    }

    public static class SubscribeRequest {
        public List<@NonNull Cursor> objects;

        public SubscribeRequest(List<@NonNull Cursor> objects) {
            this.objects = objects;
        }
    }

    public static class SubscribeResponse {
        public List<Bucket> objects;
    }

    public static class Cursor {
        @SerializedName("object_key")
        public String key;
        @SerializedName("object_revision")
        public Long revision;
        @SerializedName("object_timestamp")
        public Long timestamp;

        public Cursor(String key, Long revision, Long timestamp) {
            this.key = key;
            this.revision = revision;
            this.timestamp = timestamp;
        }
    }

    public static class Bucket {
        @SerializedName("object_key")
        public String key;
        @SerializedName("object_revision")
        public Long revision;
        @SerializedName("object_timestamp")
        public Long timestamp;
        // Bucket values have different schemas; the client selects the DTO using the object_key prefix.
        public JsonObject value;
    }

    public static class Structure {
        public String name;
        public Boolean away;
    }

    public static class Where {
        public List<Room> wheres;
    }

    public static class Room {
        @SerializedName("where_id")
        public String id;
        public String name;
    }

    public static class AwayRequest {
        public boolean away;
        @SerializedName("away_timestamp")
        public long timestamp;
        @SerializedName("away_setter")
        public int setter = 0;

        public AwayRequest(boolean away, long timestamp) {
            this.away = away;
            this.timestamp = timestamp;
        }
    }

    public static class PathlightRequest {
        @SerializedName("night_light_enable")
        public boolean enabled;
        @SerializedName("night_light_continuous")
        public boolean continuous;

        public PathlightRequest(boolean enabled, boolean continuous) {
            this.enabled = enabled;
            this.continuous = continuous;
        }
    }

    public static class Protect {
        @SerializedName("where_id")
        public String whereId;
        @SerializedName("night_light_enable")
        public Boolean nightLightEnabled;
        @SerializedName("night_light_continuous")
        public Boolean nightLightContinuous;
        @SerializedName("wired_or_battery")
        public Integer powerType;
        @SerializedName("smoke_status")
        public Integer smoke;
        @SerializedName("co_status")
        public Integer carbonMonoxide;
        @SerializedName("battery_health_state")
        public Integer battery;
        @SerializedName("line_power_present")
        public Boolean linePower;
        @SerializedName("is_online")
        public Boolean online;
        @SerializedName("replace_by_date_utc_secs")
        public Long replaceBy;
        @SerializedName("latest_manual_test_start_utc_secs")
        public Long testStarted;
        @SerializedName("latest_manual_test_end_utc_secs")
        public Long testEnded;
    }
}
