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

import com.google.common.base.*;
import com.google.common.collect.*;
import groovy.lang.GroovyObject;
import org.apache.commons.lang.StringUtils;
import org.gradle.api.Action;
import org.gradle.api.Nullable;
import org.gradle.internal.reflect.MethodDescription;
import org.gradle.internal.reflect.MethodSignatureEquivalence;
import org.gradle.model.Unmanaged;
import org.gradle.model.internal.manage.schema.ModelProperty;
import org.gradle.model.internal.manage.schema.ModelSchema;
import org.gradle.model.internal.manage.schema.ModelSchemaStore;
import org.gradle.model.internal.manage.schema.cache.ModelSchemaCache;
import org.gradle.model.internal.type.ModelType;
import org.gradle.util.CollectionUtils;

import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

public abstract class ImplTypeSchemaExtractionStrategySupport implements ModelSchemaExtractionStrategy {

    private static final Ordering<Method> RETURN_TYPE_SPECIALIZATION_ORDERING = new Ordering<Method>() {
        @Override
        public int compare(Method left, Method right) {
            Class<?> leftType = left.getReturnType();
            Class<?> rightType = right.getReturnType();
            if (leftType.equals(rightType)) {
                return 0;
            }
            if (leftType.isAssignableFrom(rightType)) {
                return -1;
            }
            if (rightType.isAssignableFrom(leftType)) {
                return 1;
            }
            throw new UnsupportedOperationException(String.format("Cannot compare two types that aren't part of an inheritance hierarchy: %s, %s", leftType, rightType));
        }
    };

    protected static final Equivalence<Method> METHOD_EQUIVALENCE = new MethodSignatureEquivalence();

    private static final Set<Equivalence.Wrapper<Method>> IGNORED_METHODS = ImmutableSet.copyOf(
        Iterables.transform(
            Iterables.concat(
                Arrays.asList(Object.class.getMethods()),
                Arrays.asList(GroovyObject.class.getMethods())
            ), new Function<Method, Equivalence.Wrapper<Method>>() {
                public Equivalence.Wrapper<Method> apply(@Nullable Method input) {
                    return METHOD_EQUIVALENCE.wrap(input);
                }
            }
        )
    );

    public <R> ModelSchemaExtractionResult<R> extract(final ModelSchemaExtractionContext<R> extractionContext, ModelSchemaStore store, final ModelSchemaCache cache) {
        ModelType<R> type = extractionContext.getType();
        Class<? super R> clazz = type.getRawClass();
        if (isTarget(type)) {
            validateType(type, extractionContext);

            // TODO:LPTR Get all methods by crawling ancestry and using getDeclaredMethods()
            // This avoids folding multiple overrides into one method, and losing annotations declared on them
            Iterable<Method> methods = getMethods(clazz);
            ImmutableListMultimap<String, Method> methodsByName = Multimaps.index(methods, new Function<Method, String>() {
                public String apply(Method method) {
                    return method.getName();
                }
            });

            ensureNoOverloadedMethods(extractionContext, methodsByName);
            // TODO:LPTR Ensure no overrides of delegate type in managed subtype

            List<ModelProperty<?>> properties = Lists.newLinkedList();
            List<Method> handled = Lists.newArrayListWithCapacity(clazz.getMethods().length);

            for (String methodName : methodsByName.keySet()) {
                if (methodName.startsWith("get") && !methodName.equals("get")) {
                    ImmutableList<Method> getterMethods = methodsByName.get(methodName);

                    // The overload check earlier verified that all methods for are equivalent for our purposes
                    // So, taking the first one with the most specialized return type is fine.
                    Method sampleMethod = RETURN_TYPE_SPECIALIZATION_ORDERING.max(getterMethods);

                    validateGetter(sampleMethod, extractionContext);

                    ModelType<?> returnType = ModelType.returnType(sampleMethod);

                    String propertyNameCapitalized = methodName.substring(3);
                    String propertyName = StringUtils.uncapitalize(propertyNameCapitalized);
                    String setterName = "set" + propertyNameCapitalized;
                    ImmutableList<Method> setterMethods = methodsByName.get(setterName);

                    boolean isWritable = !setterMethods.isEmpty();
                    if (isWritable) {
                        Method setter = setterMethods.get(0);
                        validateSetter(extractionContext, returnType, sampleMethod, setter);
                        handled.addAll(setterMethods);
                    }

                    ModelProperty.Kind kind = getPropertyKind(sampleMethod, propertyName);
                    if (kind == null) {
                        continue;
                    }

                    ImmutableSet<ModelType<?>> declaringClasses = ImmutableSet.copyOf(Iterables.transform(getterMethods, new Function<Method, ModelType<?>>() {
                        public ModelType<?> apply(Method input) {
                            return ModelType.of(input.getDeclaringClass());
                        }
                    }));

                    boolean unmanaged = Iterables.any(getterMethods, new Predicate<Method>() {
                        public boolean apply(Method input) {
                            return input.getAnnotation(Unmanaged.class) != null;
                        }
                    });

                    properties.add(ModelProperty.of(returnType, propertyName, isWritable, declaringClasses, unmanaged, kind));
                    handled.addAll(getterMethods);
                }
            }

            Iterable<Method> notHandled = filterNotHandledMethods(Iterables.filter(methodsByName.values(), Predicates.not(Predicates.in(handled))));

            // TODO - should call out valid getters without setters
            if (!Iterables.isEmpty(notHandled)) {
                throw invalidMethods(extractionContext, "only paired getter/setter methods are supported", notHandled);
            }

            Class<R> concreteClass = type.getConcreteClass();
            final ModelSchema<R> schema = createSchema(extractionContext, store, type, properties, concreteClass);
            Iterable<ModelSchemaExtractionContext<?>> propertyDependencies = Iterables.transform(properties, new Function<ModelProperty<?>, ModelSchemaExtractionContext<?>>() {
                public ModelSchemaExtractionContext<?> apply(final ModelProperty<?> property) {
                    return toPropertyExtractionContext(extractionContext, property, cache);
                }
            });

            return new ModelSchemaExtractionResult<R>(schema, propertyDependencies);
        } else {
            return null;
        }
    }

    abstract protected ModelProperty.Kind getPropertyKind(Method getter, String propertyName);

    protected Iterable<Method> filterNotHandledMethods(Iterable<Method> notHandledMethods) {
        return notHandledMethods;
    }

    protected abstract <R> ModelSchema<R> createSchema(ModelSchemaExtractionContext<R> extractionContext, ModelSchemaStore store, ModelType<R> type, List<ModelProperty<?>> properties, Class<R> concreteClass);

    private <R, P> ModelSchemaExtractionContext<P> toPropertyExtractionContext(final ModelSchemaExtractionContext<R> parentContext, final ModelProperty<P> property, final ModelSchemaCache modelSchemaCache) {
        return parentContext.child(property.getType(), propertyDescription(parentContext, property), createSchemaValidatorAction(parentContext, property, modelSchemaCache));
    }

    private String propertyDescription(ModelSchemaExtractionContext<?> parentContext, ModelProperty<?> property) {
        if (property.getDeclaredBy().size() == 1 && property.getDeclaredBy().contains(parentContext.getType())) {
            return String.format("property '%s'", property.getName());
        } else {
            ImmutableSortedSet<String> declaredBy = ImmutableSortedSet.copyOf(Iterables.transform(property.getDeclaredBy(), Functions.toStringFunction()));
            return String.format("property '%s' declared by %s", property.getName(), Joiner.on(", ").join(declaredBy));
        }
    }

    abstract protected boolean isTarget(ModelType<?> type);

    abstract protected <R, P> Action<ModelSchemaExtractionContext<P>> createSchemaValidatorAction(final ModelSchemaExtractionContext<R> parentContext, final ModelProperty<P> property, final ModelSchemaCache modelSchemaCache);

    protected abstract void validateType(ModelType<?> type, ModelSchemaExtractionContext<?> extractionContext);

    private <R> void ensureNoOverloadedMethods(ModelSchemaExtractionContext<R> extractionContext, final ImmutableListMultimap<String, Method> methodsByName) {
        ImmutableSet<String> methodNames = methodsByName.keySet();
        for (String methodName : methodNames) {
            ImmutableList<Method> methods = methodsByName.get(methodName);
            if (methods.size() > 1) {
                List<Method> deduped = CollectionUtils.dedup(methods, METHOD_EQUIVALENCE);
                if (deduped.size() > 1) {
                    throw invalidMethods(extractionContext, "overloaded methods are not supported", deduped);
                }
            }
        }
    }

    protected <R> void validateGetter(Method sampleMethod, ModelSchemaExtractionContext<R> extractionContext) {
        if (sampleMethod.getParameterTypes().length != 0) {
            throw invalidMethod(extractionContext, "getter methods cannot take parameters", sampleMethod);
        }

        Character getterPropertyNameFirstChar = sampleMethod.getName().charAt(3);
        if (!Character.isUpperCase(getterPropertyNameFirstChar)) {
            throw invalidMethod(extractionContext, "the 4th character of the getter method name must be an uppercase character", sampleMethod);
        }
    }

    protected void validateSetter(ModelSchemaExtractionContext<?> extractionContext, ModelType<?> propertyType, Method getter, Method setter) {
        if (!setter.getReturnType().equals(void.class)) {
            throw invalidMethod(extractionContext, "setter method must have void return type", setter);
        }

        Type[] setterParameterTypes = setter.getGenericParameterTypes();
        if (setterParameterTypes.length != 1) {
            throw invalidMethod(extractionContext, "setter method must have exactly one parameter", setter);
        }

        ModelType<?> setterType = ModelType.paramType(setter, 0);
        if (!setterType.equals(propertyType)) {
            String message = "setter method param must be of exactly the same type as the getter returns (expected: " + propertyType + ", found: " + setterType + ")";
            throw invalidMethod(extractionContext, message, setter);
        }
    }

    protected static Iterable<Method> getMethods(final Class<?> clazz) {
        return Iterables.filter(Arrays.asList(clazz.getMethods()), new Predicate<Method>() {
            @Override
            public boolean apply(Method method) {
                boolean include = true;
                if (!clazz.isInterface()) {
                    include = !method.isSynthetic()
                        && !IGNORED_METHODS.contains(METHOD_EQUIVALENCE.wrap(method));
                }
                return include;
            }
        });
    }


    protected InvalidManagedModelElementTypeException invalidMethod(ModelSchemaExtractionContext<?> extractionContext, String message, Method method) {
        return invalidMethod(extractionContext, message, MethodDescription.of(method));
    }

    protected InvalidManagedModelElementTypeException invalidMethod(ModelSchemaExtractionContext<?> extractionContext, String message, MethodDescription methodDescription) {
        return invalidMethod(extractionContext, message + " (invalid method: " + methodDescription.toString() + ").");
    }

    protected InvalidManagedModelElementTypeException invalidMethods(ModelSchemaExtractionContext<?> extractionContext, String message, final Iterable<Method> methods) {
        final ImmutableSortedSet<String> descriptions = ImmutableSortedSet.copyOf(Iterables.transform(methods, new Function<Method, String>() {
            public String apply(Method method) {
                return MethodDescription.of(method).toString();
            }
        }));
        return invalidMethod(extractionContext, message + " (invalid methods: " + Joiner.on(", ").join(descriptions) + ").");
    }

    abstract protected InvalidManagedModelElementTypeException invalidMethod(ModelSchemaExtractionContext<?> extractionContext, String message);
}
