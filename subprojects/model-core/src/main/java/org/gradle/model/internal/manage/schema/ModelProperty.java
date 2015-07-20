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

package org.gradle.model.internal.manage.schema;

import com.google.common.base.Function;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import net.jcip.annotations.ThreadSafe;
import org.gradle.internal.Cast;
import org.gradle.model.internal.type.ModelType;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

@ThreadSafe
public class ModelProperty<T> {

    private final String name;
    private final ModelType<T> type;
    private final boolean writable;
    private final Set<ModelType<?>> declaredBy;
    private final boolean unmanaged;
    private final Method getter;
    private final Map<Class<? extends ModelPropertyNature>, ModelPropertyNature> natures;

    private ModelProperty(ModelType<T> type, String name, boolean writable, Set<ModelType<?>> declaredBy, boolean unmanaged, Method getter, Collection<ModelPropertyNature> natures) {
        this.name = name;
        this.type = type;
        this.writable = writable;
        this.declaredBy = ImmutableSet.copyOf(declaredBy);
        this.unmanaged = unmanaged;
        this.getter = getter;
        this.natures = Maps.uniqueIndex(natures, new Function<ModelPropertyNature, Class<? extends ModelPropertyNature>>() {
            @Override
            public Class<? extends ModelPropertyNature> apply(ModelPropertyNature input) {
                return input.getClass();
            }
        });
    }

    public static <T> ModelProperty<T> of(ModelType<T> type, String name, boolean writable, Set<ModelType<?>> declaredBy, boolean unmanaged, Method getter, Collection<ModelPropertyNature> natures) {
        return new ModelProperty<T>(type, name, writable, declaredBy, unmanaged, getter, natures);
    }

    public String getName() {
        return name;
    }

    public boolean isUnmanaged() {
        return unmanaged;
    }

    public ModelType<T> getType() {
        return type;
    }

    public boolean isWritable() {
        return writable;
    }

    public Set<ModelType<?>> getDeclaredBy() {
        return declaredBy;
    }

    public Method getGetter() {
        return getter;
    }

    public boolean hasNature(Class<? extends ModelPropertyNature> natureType) {
        return natures.containsKey(natureType);
    }

    public <N extends ModelPropertyNature> N getNature(Class<N> natureType) {
        return Cast.uncheckedCast(natures.get(natureType));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        ModelProperty<?> that = (ModelProperty<?>) o;


        return name.equals(that.name) && type.equals(that.type) && writable == that.writable;
    }

    @Override
    public int hashCode() {
        int result = name.hashCode();
        result = 31 * result + type.hashCode();
        result = 31 * result + Boolean.valueOf(writable).hashCode();
        return result;
    }
}
