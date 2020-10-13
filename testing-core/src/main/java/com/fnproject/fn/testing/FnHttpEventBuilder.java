/*
 * Copyright (c) 2019, 2020 Oracle and/or its affiliates. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.fnproject.fn.testing;

import com.fnproject.fn.api.Headers;
import com.fnproject.fn.api.InputEvent;
import com.fnproject.fn.runtime.ReadOnceInputEvent;
import org.apache.commons.io.IOUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;

public class FnHttpEventBuilder {
    private byte[] bodyBytes = new byte[0];
    private Headers headers = Headers.emptyHeaders();
    private Instant deadline = Instant.now().plus(1, ChronoUnit.HOURS);

    public FnHttpEventBuilder withHeader(String key, String value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        headers = headers.addHeader(key, value);
        return this;
    }

    public FnHttpEventBuilder withBody(InputStream body) throws IOException {
        Objects.requireNonNull(body, "body");
        this.bodyBytes = IOUtils.toByteArray(body);

        return this;
    }

    public FnHttpEventBuilder withBody(byte[] body) {
        Objects.requireNonNull(body, "body");
        this.bodyBytes = body;

        return this;
    }

    public FnHttpEventBuilder withBody(String body) {
        byte stringAsBytes[] = Objects.requireNonNull(body, "body").getBytes();
        return withBody(stringAsBytes);
    }


    public FnHttpEventBuilder withHeaders(Map<String, String> headers) {
        Headers h = this.headers;
        for (Map.Entry<String, String> he : headers.entrySet()) {
            h = h.setHeader(he.getKey(), he.getValue());
        }
        this.headers = h;
        return this;
    }


    public InputEvent buildEvent() {
        return new ReadOnceInputEvent(new ByteArrayInputStream(bodyBytes), headers, "callId", deadline);
    }


}
