/*
 * Copyright 2015 the original author or authors.
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

import com.google.common.collect.ImmutableSet;
import org.gradle.api.Action;
import org.gradle.internal.Actions;
import org.gradle.model.internal.manage.schema.ModelProperty;
import org.gradle.model.internal.manage.schema.ModelSchema;
import org.gradle.model.internal.manage.schema.ModelSchemaStore;
import org.gradle.model.internal.manage.schema.cache.ModelSchemaCache;
import org.gradle.model.internal.type.ModelType;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

public class UnmanagedImplTypeSchemaExtractionStrategy extends ImplTypeSchemaExtractionStrategySupport {

    private final Class<?> type;
    private final Collection<String> recordedProperties;

    public UnmanagedImplTypeSchemaExtractionStrategy(Class<?> type, String... recordedProperties) {
        this(type, ImmutableSet.copyOf(recordedProperties));
    }

    public UnmanagedImplTypeSchemaExtractionStrategy(Class<?> type, Collection<String> recordedProperties) {
        this.type = type;
        this.recordedProperties = ImmutableSet.copyOf(recordedProperties);
    }

    protected boolean isTarget(ModelType<?> type) {
        return this.type.isAssignableFrom(type.getRawClass());
    }

    @Override
    protected ModelProperty.Kind getPropertyKind(Method getter, String propertyName) {
        if (recordedProperties.contains(propertyName)) {
            return ModelProperty.Kind.UNMANAGED;
        }
        return null;
    }

    @Override
    protected Iterable<Method> filterNotHandledMethods(Iterable<Method> notHandledMethods) {
        return Collections.emptySet();
    }

    @Override
    protected <R, P> Action<ModelSchemaExtractionContext<P>> createSchemaValidatorAction(ModelSchemaExtractionContext<R> parentContext, ModelProperty<P> property, ModelSchemaCache modelSchemaCache) {
        return Actions.doNothing();
    }

    @Override
    protected <R> ModelSchema<R> createSchema(ModelSchemaExtractionContext<R> extractionContext, ModelSchemaStore store, ModelType<R> type, List<ModelProperty<?>> properties, Class<R> concreteClass) {
        return ModelSchema.unmanagedInstance(type, properties);
    }

    @Override
    protected void validateType(ModelType<?> type, ModelSchemaExtractionContext<?> extractionContext) {
        Class<?> typeClass = type.getConcreteClass();

        if (typeClass.isInterface() || Modifier.isAbstract(typeClass.getModifiers())) {
            throw new InvalidManagedModelElementTypeException(extractionContext, "must be defined as a concrete class.");
        }

        if (typeClass.getTypeParameters().length > 0) {
            throw new InvalidManagedModelElementTypeException(extractionContext, "cannot be a parameterized type.");
        }
    }

    @Override
    protected InvalidManagedModelElementTypeException invalidMethod(ModelSchemaExtractionContext<?> extractionContext, String message) {
        return new UnmanagedModelElementTypeException(extractionContext, message);
    }
}
