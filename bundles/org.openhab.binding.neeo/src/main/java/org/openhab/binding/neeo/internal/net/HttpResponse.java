/*
 * Copyright (c) 2010-2025 Contributors to the openHAB project
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
package org.openhab.binding.neeo.internal.net;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import javax.ws.rs.core.Response;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.client.api.ContentResponse;

/**
 * This class represents an {@link HttpRequest} response
 *
 * @author Tim Roberts - Initial contribution
 */
@NonNullByDefault
public class HttpResponse {

    /** The http status */
    private final int httpStatus;

    /** The http reason */
    private final String httpReason;

    /** The http headers */
    private final Map<String, String> headers = new HashMap<>();

    /** The contents as a raw byte array */
    private final byte @Nullable [] contents;

    /**
     * Instantiates a new http response from the {@link Response}.
     *
     * @param refreshResponse the non-null response
     * @throws IOException Signals that an I/O exception has occurred.
     */
    HttpResponse(ContentResponse refreshResponse) throws IOException {
        Objects.requireNonNull(refreshResponse, "response cannot be null");

        httpStatus = refreshResponse.getStatus();
        httpReason = refreshResponse.getReason();
        contents = refreshResponse.getContent();

        for (String key : refreshResponse.getHeaders().getFieldNamesCollection()) {
            headers.put(key, refreshResponse.getHeaders().getField(key).toString());
        }
    }

    /**
     * Instantiates a new http response.
     *
     * @param httpCode the http code
     * @param msg the msg
     */
    HttpResponse(int httpCode, @Nullable String msg) {
        httpStatus = httpCode;
        httpReason = msg != null ? msg : "";
        contents = null;
    }

    /**
     * Gets the HTTP status code.
     *
     * @return the HTTP status code
     */
    public int getHttpCode() {
        return httpStatus;
    }

    /**
     * Gets the content.
     *
     * @return the content
     */
    public String getContent() {
        final byte[] localContents = contents;
        if (localContents == null || localContents.length == 0) {
            return "";
        }

        return new String(localContents, StandardCharsets.UTF_8);
    }

    /**
     * Creates an {@link IOException} from the {@link #httpReason}
     *
     * @return the IO exception
     */
    public IOException createException() {
        return new IOException(httpReason);
    }

    @Override
    public String toString() {
        return getHttpCode() + " (" + (contents == null ? ("http reason: " + httpReason) : getContent()) + ")";
    }
}
