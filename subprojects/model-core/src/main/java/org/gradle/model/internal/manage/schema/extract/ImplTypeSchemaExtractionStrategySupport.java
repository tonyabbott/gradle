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
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import groovy.lang.GroovyObject;
import org.gradle.api.Nullable;
import org.gradle.internal.reflect.MethodSignatureEquivalence;
import org.gradle.model.internal.manage.IgnoreInModelSchema;

import java.lang.reflect.Method;
import java.util.*;

public abstract class ImplTypeSchemaExtractionStrategySupport implements ModelSchemaExtractionStrategy {
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

    protected List<Method> getCandidateMethods(Class<?> clazz) {
        List<Method> allMethods = Lists.newArrayList();
        Set<Class<?>> visitedInterfaces = Sets.newHashSet();
        visitClass(clazz, allMethods, visitedInterfaces);
        return allMethods;
    }

    private void visitClass(Class<?> clazz, List<Method> allMethods, Set<Class<?>> visitedInterfaces) {
        // Do not process Object's or GroovyObject's methods
        if (clazz.equals(Object.class) || clazz.equals(GroovyObject.class)) {
            return;
        }
        visitType(clazz, allMethods, visitedInterfaces);

        Class<?> superclass = clazz.getSuperclass();
        if (superclass != null) {
            visitClass(superclass, allMethods, visitedInterfaces);
        }
    }

    private void visitType(Class<?> clazz, List<Method> allMethods, Set<Class<?>> visitedInterfaces) {
        for (Method method : clazz.getDeclaredMethods()) {
            if (!clazz.isInterface()) {
                // Ignore generated methods
                if (method.isSynthetic()) {
                    continue;
                }
                // Ignore overrides of Object and GroovyObject methods
                if (IGNORED_METHODS.contains(METHOD_EQUIVALENCE.wrap(method))) {
                    continue;
                }
            }
            if (method.isAnnotationPresent(IgnoreInModelSchema.class)) {
                continue;
            }

            allMethods.add(method);
        }
        for (Class<?> iface : clazz.getInterfaces()) {
            if (visitedInterfaces.add(iface)) {
                visitType(iface, allMethods, visitedInterfaces);
            }
        }
    }
}
