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

package org.gradle.jvm.internal.model
import org.gradle.jvm.JarBinarySpec
import org.gradle.jvm.internal.DefaultJarBinarySpec
import org.gradle.model.Managed
import org.gradle.model.internal.manage.schema.ModelSchema
import org.gradle.model.internal.manage.schema.extract.DefaultModelSchemaStore
import org.gradle.model.internal.manage.schema.extract.ModelSchemaExtractor
import org.gradle.model.internal.manage.schema.extract.UnmanagedImplTypeSchemaExtractionStrategy
import spock.lang.Shared
import spock.lang.Specification

class JarBinarySpecSpecializationSchemaExtractionStrategyTest extends Specification {
    def classLoader = new GroovyClassLoader(getClass().classLoader)
    @Shared
    def extractor = new ModelSchemaExtractor([
        new JarBinarySpecSpecializationSchemaExtractionStrategy(),
        new UnmanagedImplTypeSchemaExtractionStrategy(DefaultJarBinarySpec, "targetPlatform")
    ])
    @Shared
    def store = new DefaultModelSchemaStore(extractor)

    @Managed
    interface MyJarBinarySpec extends JarBinarySpec {
        String getValue()
        void setValue(String value)
    }

    static class MyUnmanagedJarBinarySpec extends DefaultJarBinarySpec {
        private String value

        String getValue() {
            return value
        }
        void setValue(String value) {
            this.value = value
        }
    }

    def "can extract schema from custom managed Jar binary"() {
        when:
        def schema = store.getSchema(MyJarBinarySpec)

        then:
        schema.getKind() == ModelSchema.Kind.STRUCT
        schema.properties.keySet() == ["targetPlatform", "value"] as Set
    }

    def "can extract schema from custom unmanaged Jar binary"() {
        when:
        def schema = store.getSchema(MyUnmanagedJarBinarySpec)

        then:
        schema.getKind() == ModelSchema.Kind.UNMANAGED
        schema.properties.keySet() == ["targetPlatform"] as Set
    }
}
