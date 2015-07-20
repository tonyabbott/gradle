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

import org.gradle.model.internal.manage.schema.ModelPropertyNature;

public class ModelPropertyNatureExtractionResult {
    private final Iterable<? extends ModelPropertyNature> extractedNatures;

    public ModelPropertyNatureExtractionResult(Iterable<? extends ModelPropertyNature> extractedNatures) {
        this.extractedNatures = extractedNatures;
    }

    public Iterable<? extends ModelPropertyNature> getExtractedNatures() {
        return extractedNatures;
    }
}
