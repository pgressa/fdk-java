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

package com.fnproject.springframework.function.functions;

import com.fnproject.fn.api.TypeWrapper;
import com.fnproject.springframework.function.SimpleTypeWrapper;
import org.springframework.cloud.function.context.catalog.FunctionInspector;
import reactor.core.publisher.Flux;

import java.util.function.Consumer;

/**
 * {@link SpringCloudMethod} representing a {@link Consumer}
 */
public class SpringCloudConsumer extends SpringCloudMethod {
    private Consumer<Flux<?>> consumer;

    public SpringCloudConsumer(Consumer<Flux<?>> consumer, FunctionInspector inspector) {
        super(inspector);
        this.consumer = consumer;
    }

    @Override
    protected String getMethodName() {
        return "accept";
    }

    @Override
    protected Object getFunction() {
        return consumer;
    }

    @Override
    public TypeWrapper getReturnType() {
        return new SimpleTypeWrapper(Void.class);
    }
    @Override
    public Flux<?> invoke(Flux<?> arg) {
        consumer.accept(arg);
        return Flux.empty();
    }

}
