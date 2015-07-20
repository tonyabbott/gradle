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
import org.apache.commons.lang.StringUtils;
import org.gradle.internal.reflect.MethodDescription;
import org.gradle.internal.reflect.MethodSignatureEquivalence;
import org.gradle.model.Unmanaged;
import org.gradle.model.internal.manage.schema.ModelProperty;
import org.gradle.model.internal.manage.schema.ModelPropertyNature;
import org.gradle.model.internal.type.ModelType;
import org.gradle.util.CollectionUtils;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.List;

public class ModelPropertiesExtractor {
    private static final Equivalence<Method> METHOD_EQUIVALENCE = new MethodSignatureEquivalence();

    /**
     * Orders methods based on their return types, more specific types preceding more general types.
     */
    private static final Ordering<Method> RETURN_TYPE_SPECIALIZATION_ORDERING = new Ordering<Method>() {
        @Override
        public int compare(Method left, Method right) {
            Class<?> leftType = left.getReturnType();
            Class<?> rightType = right.getReturnType();
            if (leftType.equals(rightType)) {
                return 0;
            }
            if (leftType.isAssignableFrom(rightType)) {
                return 1;
            }
            if (rightType.isAssignableFrom(leftType)) {
                return -1;
            }
            throw new UnsupportedOperationException(String.format("Cannot compare two types that aren't part of an inheritance hierarchy: %s, %s", leftType, rightType));
        }
    };

    private final ModelPropertyNatureExtractor propertyNatureExtractor;

    public ModelPropertiesExtractor(ModelPropertyNatureExtractor propertyNatureExtractor) {
        this.propertyNatureExtractor = propertyNatureExtractor;
    }

    public <T> ModelPropertiesExtractionResult extract(ModelSchemaExtractionContext<T> extractionContext, Iterable<Method> methods) {
        ImmutableListMultimap<String, Method> methodsByName = Multimaps.index(methods, new Function<Method, String>() {
            public String apply(Method method) {
                return method.getName();
            }
        });

        ensureNoOverloadedMethods(extractionContext, methodsByName);

        List<ModelProperty<?>> properties = Lists.newLinkedList();
        List<Method> handled = Lists.newArrayListWithCapacity(methodsByName.keySet().size());

        for (String methodName : methodsByName.keySet()) {
            if (methodName.startsWith("get") && !methodName.equals("get")) {
                List<Method> getterMethods = methodsByName.get(methodName);
                if (getterMethods.size() > 1) {
                    getterMethods = Lists.newArrayList(getterMethods);
                    getterMethods.sort(RETURN_TYPE_SPECIALIZATION_ORDERING);
                }

                // The overload check earlier verified that all methods for are equivalent for our purposes
                // So, taking one with the most specialized return type is fine.
                Method sampleMethod = getterMethods.get(0);

                boolean abstractGetter = Modifier.isAbstract(sampleMethod.getModifiers());

                if (sampleMethod.getParameterTypes().length != 0) {
                    throw invalidMethod(extractionContext, "getter methods cannot take parameters", sampleMethod);
                }

                Character getterPropertyNameFirstChar = methodName.charAt(3);
                if (!Character.isUpperCase(getterPropertyNameFirstChar)) {
                    throw invalidMethod(extractionContext, "the 4th character of the getter method name must be an uppercase character", sampleMethod);
                }

                ModelType<?> returnType = ModelType.returnType(sampleMethod);

                String propertyNameCapitalized = methodName.substring(3);
                String propertyName = StringUtils.uncapitalize(propertyNameCapitalized);
                String setterName = "set" + propertyNameCapitalized;
                ImmutableList<Method> setterMethods = methodsByName.get(setterName);

                boolean isWritable = !setterMethods.isEmpty();
                if (isWritable) {
                    Method setter = setterMethods.get(0);

                    if (!abstractGetter) {
                        validateSetterForNonAbstractGetter(extractionContext, setter);
                    }
                    validateSetter(extractionContext, returnType, setter);
                    handled.addAll(setterMethods);
                }

                boolean unmanaged;
                if (abstractGetter) {
                    unmanaged = Iterables.any(getterMethods, new Predicate<Method>() {
                        public boolean apply(Method input) {
                            return input.getAnnotation(Unmanaged.class) != null;
                        }
                    });
                } else {
                    unmanaged = true;
                }

                ImmutableSet<ModelType<?>> declaringClasses = ImmutableSet.copyOf(Iterables.transform(getterMethods, new Function<Method, ModelType<?>>() {
                    public ModelType<?> apply(Method input) {
                        return ModelType.of(input.getDeclaringClass());
                    }
                }));
                Collection<ModelPropertyNature> natures = propertyNatureExtractor.extract(getterMethods);
                properties.add(ModelProperty.of(returnType, propertyName, isWritable, declaringClasses, unmanaged, sampleMethod, natures));

                handled.addAll(getterMethods);
            }
        }

        Iterable<Method> notHandled = Iterables.filter(methodsByName.values(), Predicates.not(Predicates.in(handled)));
        return new ModelPropertiesExtractionResult(properties, notHandled);
    }

    protected <T> void validateSetterForNonAbstractGetter(ModelSchemaExtractionContext<T> extractionContext, Method setter) {
    }

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

    protected void validateSetter(ModelSchemaExtractionContext<?> extractionContext, ModelType<?> propertyType, Method setter) {
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

    protected InvalidManagedModelElementTypeException invalidMethod(ModelSchemaExtractionContext<?> extractionContext, String message, Method method) {
        return invalidMethod(extractionContext, message, MethodDescription.of(method));
    }

    private InvalidManagedModelElementTypeException invalidMethod(ModelSchemaExtractionContext<?> extractionContext, String message, MethodDescription methodDescription) {
        return new InvalidManagedModelElementTypeException(extractionContext, message + " (invalid method: " + methodDescription.toString() + ").");
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
