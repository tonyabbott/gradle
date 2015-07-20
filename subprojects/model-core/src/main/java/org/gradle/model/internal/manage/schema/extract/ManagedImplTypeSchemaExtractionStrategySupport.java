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
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import groovy.lang.GroovyObject;
import org.gradle.api.Action;
import org.gradle.api.Named;
import org.gradle.api.Nullable;
import org.gradle.internal.reflect.MethodDescription;
import org.gradle.internal.reflect.MethodSignatureEquivalence;
import org.gradle.model.Managed;
import org.gradle.model.internal.manage.schema.ModelProperty;
import org.gradle.model.internal.manage.schema.ModelSchema;
import org.gradle.model.internal.manage.schema.ModelSchemaStore;
import org.gradle.model.internal.manage.schema.cache.ModelSchemaCache;
import org.gradle.model.internal.type.ModelType;

import java.lang.reflect.*;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

public abstract class ManagedImplTypeSchemaExtractionStrategySupport implements ModelSchemaExtractionStrategy {

    private static final Equivalence<Method> METHOD_EQUIVALENCE = new MethodSignatureEquivalence();

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

    protected boolean isTarget(ModelType<?> type) {
        return type.getRawClass().isAnnotationPresent(Managed.class);
    }

    public <R> ModelSchemaExtractionResult<R> extract(final ModelSchemaExtractionContext<R> extractionContext, ModelSchemaStore store, final ModelSchemaCache cache, ModelPropertyNatureExtractor propertyNatureExtractor) {
        ModelType<R> type = extractionContext.getType();
        if (isTarget(type)) {
            validateType(type, extractionContext);

            Iterable<Method> methods = getManagedMethods(type.getRawClass());

            ManagedModelPropertiesExtractor propertiesExtractor = new ManagedModelPropertiesExtractor(propertyNatureExtractor);
            ModelPropertiesExtractionResult propertyExtractionResult = propertiesExtractor.extract(extractionContext, methods);

            Iterable<ModelProperty<?>> properties = propertyExtractionResult.getProperties();
            Iterable<Method> notHandled = propertyExtractionResult.getNotHandled();

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

    protected abstract <R> ModelSchema<R> createSchema(ModelSchemaExtractionContext<R> extractionContext, ModelSchemaStore store, ModelType<R> type, Iterable<ModelProperty<?>> properties, Class<R> concreteClass);

    private Iterable<Method> getManagedMethods(final Class<?> clazz) {
        return Iterables.filter(Arrays.asList(clazz.getMethods()), new Predicate<Method>() {
            @Override
            public boolean apply(Method method) {
                boolean include = true;
                if (!clazz.isInterface()) {
                    include = !method.isSynthetic()
                        && !IGNORED_METHODS.contains(METHOD_EQUIVALENCE.wrap(method));
                }
                if (include) {
                    include = !ignoreMethod(method);
                }
                return include;
            }
        });
    }

    protected boolean ignoreMethod(Method method) {
        return false;
    }

    private <R, P> ModelSchemaExtractionContext<P> toPropertyExtractionContext(final ModelSchemaExtractionContext<R> parentContext, final ModelProperty<P> property, final ModelSchemaCache modelSchemaCache) {
        return parentContext.child(property.getType(), propertyDescription(parentContext, property), new Action<ModelSchemaExtractionContext<P>>() {
            public void execute(ModelSchemaExtractionContext<P> propertyExtractionContext) {
                ModelSchema<P> propertySchema = modelSchemaCache.get(property.getType());

                if (property.getName().equals("name") && Named.class.isAssignableFrom(parentContext.getType().getRawClass())) {
                    if (property.isWritable()) {
                        throw new InvalidManagedModelElementTypeException(parentContext, String.format(
                            "@Managed types implementing %s must not declare a setter for the name property",
                            Named.class.getName()
                        ));
                    } else {
                        return;
                    }
                }

                if (propertySchema.getKind().isAllowedPropertyTypeOfManagedType() && property.isUnmanaged()) {
                    throw new InvalidManagedModelElementTypeException(parentContext, String.format(
                        "property '%s' is marked as @Unmanaged, but is of @Managed type '%s'. Please remove the @Managed annotation.%n",
                        property.getName(), property.getType()
                    ));
                }

                if (!propertySchema.getKind().isAllowedPropertyTypeOfManagedType() && !property.isUnmanaged()) {
                    throw new InvalidManagedModelElementTypeException(parentContext, String.format(
                        "type %s cannot be used for property '%s' as it is an unmanaged type (please annotate the getter with @org.gradle.model.Unmanaged if you want this property to be unmanaged).%n%s",
                        property.getType(), property.getName(), ModelSchemaExtractor.getManageablePropertyTypesDescription()
                    ));
                }

                if (!property.isWritable()) {
                    if (property.isUnmanaged()) {
                        throw new InvalidManagedModelElementTypeException(parentContext, String.format(
                            "unmanaged property '%s' cannot be read only, unmanaged properties must have setters",
                            property.getName())
                        );
                    }

                    if (!propertySchema.getKind().isManaged()) {
                        throw new InvalidManagedModelElementTypeException(parentContext, String.format(
                            "read only property '%s' has non managed type %s, only managed types can be used",
                            property.getName(), property.getType()));
                    }
                }

                if (propertySchema.getKind() == ModelSchema.Kind.COLLECTION) {
                    if (property.isWritable()) {
                        throw new InvalidManagedModelElementTypeException(parentContext, String.format(
                            "property '%s' cannot have a setter (%s properties must be read only).",
                            property.getName(), property.getType().toString()));
                    }
                }
            }
        });
    }

    private String propertyDescription(ModelSchemaExtractionContext<?> parentContext, ModelProperty<?> property) {
        if (property.getDeclaredBy().size() == 1 && property.getDeclaredBy().contains(parentContext.getType())) {
            return String.format("property '%s'", property.getName());
        } else {
            ImmutableSortedSet<String> declaredBy = ImmutableSortedSet.copyOf(Iterables.transform(property.getDeclaredBy(), Functions.toStringFunction()));
            return String.format("property '%s' declared by %s", property.getName(), Joiner.on(", ").join(declaredBy));
        }
    }

    private void validateType(ModelType<?> type, ModelSchemaExtractionContext<?> extractionContext) {
        Class<?> typeClass = type.getConcreteClass();

        if (!typeClass.isInterface() && !Modifier.isAbstract(typeClass.getModifiers())) {
            throw new InvalidManagedModelElementTypeException(extractionContext, "must be defined as an interface or an abstract class.");
        }

        if (typeClass.getTypeParameters().length > 0) {
            throw new InvalidManagedModelElementTypeException(extractionContext, "cannot be a parameterized type.");
        }

        Constructor<?> customConstructor = findCustomConstructor(typeClass);
        if (customConstructor != null) {
            throw invalidMethod(extractionContext, "custom constructors are not allowed", customConstructor);
        }

        ensureNoInstanceScopedFields(extractionContext, typeClass);
        ensureNoProtectedOrPrivateMethods(extractionContext, typeClass);
    }

    private void ensureNoProtectedOrPrivateMethods(ModelSchemaExtractionContext<?> extractionContext, Class<?> typeClass) {
        Class<?> superClass = typeClass.getSuperclass();
        if (superClass != null && !superClass.equals(Object.class)) {
            ensureNoProtectedOrPrivateMethods(extractionContext, superClass);
        }

        Iterable<Method> protectedAndPrivateMethods = Iterables.filter(Arrays.asList(typeClass.getDeclaredMethods()), new Predicate<Method>() {
            @Override
            public boolean apply(Method method) {
                int modifiers = method.getModifiers();
                return !method.isSynthetic() && (Modifier.isProtected(modifiers) || Modifier.isPrivate(modifiers));
            }
        });

        if (!Iterables.isEmpty(protectedAndPrivateMethods)) {
            throw invalidMethods(extractionContext, "protected and private methods are not allowed", protectedAndPrivateMethods);
        }
    }

    private void ensureNoInstanceScopedFields(ModelSchemaExtractionContext<?> extractionContext, Class<?> typeClass) {
        Class<?> superClass = typeClass.getSuperclass();
        if (superClass != null && !superClass.equals(Object.class)) {
            ensureNoInstanceScopedFields(extractionContext, superClass);
        }

        List<Field> declaredFields = Arrays.asList(typeClass.getDeclaredFields());
        Iterable<Field> instanceScopedFields = Iterables.filter(declaredFields, new Predicate<Field>() {
            public boolean apply(Field field) {
                return !Modifier.isStatic(field.getModifiers()) && !field.getName().equals("metaClass");
            }
        });
        ImmutableSortedSet<String> sortedDescriptions = ImmutableSortedSet.copyOf(Iterables.transform(instanceScopedFields, new Function<Field, String>() {
            public String apply(Field field) {
                return field.toString();
            }
        }));
        if (!sortedDescriptions.isEmpty()) {
            throw new InvalidManagedModelElementTypeException(extractionContext, "instance scoped fields are not allowed (found fields: " + Joiner.on(", ").join(sortedDescriptions) + ").");
        }
    }

    private Constructor<?> findCustomConstructor(Class<?> typeClass) {
        Class<?> superClass = typeClass.getSuperclass();
        if (superClass != null && !superClass.equals(Object.class)) {
            Constructor<?> customSuperConstructor = findCustomConstructor(typeClass.getSuperclass());
            if (customSuperConstructor != null) {
                return customSuperConstructor;
            }
        }
        Constructor<?>[] constructors = typeClass.getConstructors();
        if (constructors.length == 0 || (constructors.length == 1 && constructors[0].getParameterTypes().length == 0)) {
            return null;
        } else {
            for (Constructor<?> constructor : constructors) {
                if (constructor.getParameterTypes().length > 0) {
                    return constructor;
                }
            }
            //this should never happen
            throw new RuntimeException(String.format("Expected a constructor taking at least one argument in %s but no such constructors were found", typeClass.getName()));
        }
    }

    private InvalidManagedModelElementTypeException invalidMethod(ModelSchemaExtractionContext<?> extractionContext, String message, Method method) {
        return invalidMethod(extractionContext, message, MethodDescription.of(method));
    }

    private InvalidManagedModelElementTypeException invalidMethod(ModelSchemaExtractionContext<?> extractionContext, String message, Constructor<?> constructor) {
        return invalidMethod(extractionContext, message, MethodDescription.of(constructor));
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
