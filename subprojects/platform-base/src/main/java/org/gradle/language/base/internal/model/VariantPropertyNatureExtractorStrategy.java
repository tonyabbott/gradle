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

package org.gradle.language.base.internal.model;

import org.gradle.api.Nullable;
import org.gradle.model.internal.manage.schema.extract.ModelPropertyNatureExtractionContext;
import org.gradle.model.internal.manage.schema.extract.ModelPropertyNatureExtractionResult;
import org.gradle.model.internal.manage.schema.extract.ModelPropertyNatureExtractionStrategy;
import org.gradle.platform.base.Variant;

import java.lang.reflect.Method;
import java.util.Collections;

public class VariantPropertyNatureExtractorStrategy implements ModelPropertyNatureExtractionStrategy {
    @Nullable
    @Override
    public ModelPropertyNatureExtractionResult extract(ModelPropertyNatureExtractionContext extractionContext) {
        for (Method getter : extractionContext.getGetterMethods()) {
            if (getter.isAnnotationPresent(Variant.class)) {
                return new ModelPropertyNatureExtractionResult(Collections.singleton(VariantNature.INSTANCE));
            }
        }
        return null;
    }
}
