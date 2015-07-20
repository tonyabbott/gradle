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

package org.gradle.model.internal.manage.schema.extract

import org.gradle.model.internal.manage.schema.ModelPropertyNature
import spock.lang.Specification

class ModelPropertyNatureExtractorTest extends Specification {
    def "extractor doesn't extract anything without strategies"() {
        given:
        def extractor = new ModelPropertyNatureExtractor()

        expect:
        extractor.extract(SomeType.class.methods as List) == []
    }

    def "can extract natures"() {
        given:
        def strategy1 = Mock(ModelPropertyNatureExtractionStrategy)
        def strategy2 = Mock(ModelPropertyNatureExtractionStrategy)
        def extractor = new ModelPropertyNatureExtractor([strategy1, strategy2])

        when:
        def result = extractor.extract(SomeType.class.methods as List)

        then:
        result*.data == ["data1", "data2"]
        1 * strategy1.extract(_) >> { ModelPropertyNatureExtractionContext context ->
            context.getterMethods == SomeType.class.methods as List
            return new ModelPropertyNatureExtractionResult([new SomeNature(data: "data1")])
        }
        1 * strategy2.extract(_) >> { ModelPropertyNatureExtractionContext context ->
            context.getterMethods == SomeType.class.methods as List
            return new ModelPropertyNatureExtractionResult([new SomeNature(data: "data2")])
        }
    }

    class SomeType {
        String getValue() { return "value" }
    }

    class SomeNature implements ModelPropertyNature {
        String data
    }
}
