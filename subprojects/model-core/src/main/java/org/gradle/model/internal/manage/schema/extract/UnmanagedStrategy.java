/*
 * Copyright 2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.model.internal.manage.schema.extract;

import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import org.gradle.internal.reflect.MethodDescription;
import org.gradle.model.internal.manage.schema.ModelProperty;
import org.gradle.model.internal.manage.schema.ModelSchema;
import org.gradle.model.internal.manage.schema.ModelSchemaStore;
import org.gradle.model.internal.manage.schema.cache.ModelSchemaCache;
import org.gradle.model.internal.type.ModelType;

import java.lang.reflect.Method;
import java.util.List;

public class UnmanagedStrategy extends ImplTypeSchemaExtractionStrategySupport {
    public <R> ModelSchemaExtractionResult<R> extract(ModelSchemaExtractionContext<R> extractionContext, ModelSchemaStore store, ModelSchemaCache cache, ModelPropertyNatureExtractor propertyNatureExtractor) {
        ModelType<R> type = extractionContext.getType();

        List<Method> allMethods = getCandidateMethods(type.getRawClass());

        Iterable<Method> methods = Iterables.filter(allMethods, new Predicate<Method>() {
            @Override
            public boolean apply(Method method) {
                String name = method.getName();
                if (name.length() <= 3) {
                    return false;
                }
                if (name.startsWith("get")) {
                    return method.getParameterTypes().length == 0;
                }
                if (name.startsWith("set")) {
                    return method.getParameterTypes().length == 1 && method.getReturnType() == Void.TYPE;
                }
                return false;
            }
        });

        ModelPropertiesExtractor propertiesExtractor = new ModelPropertiesExtractor(propertyNatureExtractor);
        ModelPropertiesExtractionResult propertyExtractionResult = propertiesExtractor.extract(extractionContext, methods);

        Iterable<ModelProperty<?>> properties = propertyExtractionResult.getProperties();

        final ModelSchema<R> schema = ModelSchema.unmanaged(type, properties);
        // TODO:LPTR Do we need the schemas for properties?
//        Iterable<ModelSchemaExtractionContext<?>> propertyDependencies = Iterables.transform(properties, new Function<ModelProperty<?>, ModelSchemaExtractionContext<?>>() {
//            public ModelSchemaExtractionContext<?> apply(final ModelProperty<?> property) {
//                return toPropertyExtractionContext(extractionContext, property, cache);
//            }
//        });

        return new ModelSchemaExtractionResult<R>(schema);
    }

    private InvalidManagedModelElementTypeException invalidMethods(ModelSchemaExtractionContext<?> extractionContext, String message, final Iterable<Method> methods) {
        final ImmutableSortedSet<String> descriptions = ImmutableSortedSet.copyOf(Iterables.transform(methods, new Function<Method, String>() {
            public String apply(Method method) {
                return MethodDescription.of(method).toString();
            }
        }));
        return new InvalidManagedModelElementTypeException(extractionContext, message + " (invalid methods: " + Joiner.on(", ").join(descriptions) + ").");
    }
}
