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

import com.google.common.base.Equivalence;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import org.gradle.api.Action;
import org.gradle.api.Named;
import org.gradle.api.Nullable;
import org.gradle.internal.reflect.MethodDescription;
import org.gradle.model.Managed;
import org.gradle.model.internal.manage.schema.ModelProperty;
import org.gradle.model.internal.manage.schema.ModelSchema;
import org.gradle.model.internal.manage.schema.cache.ModelSchemaCache;
import org.gradle.model.internal.type.ModelType;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public abstract class ManagedImplTypeSchemaExtractionStrategySupport extends ImplTypeSchemaExtractionStrategySupport {

    private final Set<Equivalence.Wrapper<Method>> delegateMethods;
    private final Class<?> delegateType;

    public ManagedImplTypeSchemaExtractionStrategySupport() {
        this(null);
    }

    public ManagedImplTypeSchemaExtractionStrategySupport(Class<?> delegateType) {
        this.delegateType = delegateType;
        if (delegateType != null) {
            this.delegateMethods = ImmutableSet.copyOf(Iterables.transform(Arrays.asList(delegateType.getMethods()), new Function<Method, Equivalence.Wrapper<Method>>() {
                @Override
                public Equivalence.Wrapper<Method> apply(@Nullable Method method) {
                    return METHOD_EQUIVALENCE.wrap(method);
                }
            }));
        } else {
            this.delegateMethods = Collections.emptySet();
        }
    }

    @Override
    protected ModelProperty.Kind getPropertyKind(Method getter, String propertyName) {
        boolean delegateGetter = hasDelegateImplementation(getter);
        if (delegateGetter && ignoreDelegatedProperty(propertyName)) {
            return null;
        }

        boolean abstractGetter = Modifier.isAbstract(getter.getModifiers());
        if (abstractGetter) {
            if (delegateGetter) {
                return ModelProperty.Kind.DELEGATED;
            } else {
                return ModelProperty.Kind.MANAGED;
            }
        } else {
            return ModelProperty.Kind.UNMANAGED;
        }
    }

    @Override
    protected boolean isTarget(ModelType<?> type) {
        if (!type.getRawClass().isAnnotationPresent(Managed.class)) {
            return false;
        }
        if (delegateType != null) {
            if (type.getRawClass().equals(delegateType)
                || !delegateType.isAssignableFrom(type.getRawClass())) {
                return false;
            }
        }
        return true;
    }

    private boolean hasDelegateImplementation(Method method) {
        return delegateMethods.contains(METHOD_EQUIVALENCE.wrap(method));
    }

    protected boolean ignoreDelegatedProperty(String property) {
        return true;
    }

    @Override
    protected Iterable<Method> filterNotHandledMethods(Iterable<Method> notHandledMethods) {
        return Iterables.filter(notHandledMethods, new Predicate<Method>() {
            @Override
            public boolean apply(Method method) {
                return !hasDelegateImplementation(method);
            }
        });
    }

    @Override
    protected <R, P> Action<ModelSchemaExtractionContext<P>> createSchemaValidatorAction(final ModelSchemaExtractionContext<R> parentContext, final ModelProperty<P> property, final ModelSchemaCache modelSchemaCache) {
        return new Action<ModelSchemaExtractionContext<P>>() {
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

                // Only check managed properties
                if (property.getKind() != ModelProperty.Kind.MANAGED) {
                    return;
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
        };
    }

    @Override
    protected void validateType(ModelType<?> type, ModelSchemaExtractionContext<?> extractionContext) {
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

    @Override
    protected void validateSetter(ModelSchemaExtractionContext<?> extractionContext, ModelType<?> propertyType, Method getter, Method setter) {
        super.validateSetter(extractionContext, propertyType, getter, setter);

        boolean abstractGetter = Modifier.isAbstract(getter.getModifiers());
        boolean abstractSetter = Modifier.isAbstract(setter.getModifiers());

        if (!abstractGetter) {
            throw invalidMethod(extractionContext, "setters are not allowed for non-abstract getters", setter);
        }
        if (!abstractSetter) {
            throw invalidMethod(extractionContext, "non-abstract setters are not allowed", setter);
        }

        boolean delegateGetter = hasDelegateImplementation(getter);
        boolean delegateSetter = hasDelegateImplementation(setter);
        if (delegateGetter && !delegateSetter) {
            throw invalidMethod(extractionContext, "delegated getter should not have a non-delegated setter", setter);
        }
        if (!delegateGetter && delegateSetter) {
            throw invalidMethod(extractionContext, "delegated setter should not have a non-delegated getter", setter);
        }
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

    protected InvalidManagedModelElementTypeException invalidMethod(ModelSchemaExtractionContext<?> extractionContext, String message, Constructor<?> constructor) {
        return invalidMethod(extractionContext, message, MethodDescription.of(constructor));
    }

    @Override
    protected InvalidManagedModelElementTypeException invalidMethod(ModelSchemaExtractionContext<?> extractionContext, String message) {
        return new InvalidManagedModelElementTypeException(extractionContext, message);
    }
}
