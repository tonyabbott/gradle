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

package org.gradle.language.java
import org.gradle.integtests.fixtures.AbstractIntegrationSpec

class CustomComponentCustomBinariesIntegrationTest extends AbstractIntegrationSpec {
    def "custom component with custom JAR binary type defined by plugin is built from Java source" () {
        given:
        buildFile << """
import org.gradle.internal.service.ServiceRegistry
import org.gradle.jvm.internal.*
import org.gradle.jvm.internal.toolchain.*
import org.gradle.jvm.platform.internal.*
import org.gradle.jvm.toolchain.*
import org.gradle.jvm.toolchain.internal.*
import org.gradle.platform.base.internal.*

plugins {
    id 'jvm-component'
    id 'java-lang'
}

interface SampleLibrarySpec extends ComponentSpec {}
class DefaultSampleLibrarySpec extends BaseComponentSpec implements SampleLibrarySpec {}

interface SampleJarBinarySpec extends JarBinarySpec {
    String getSampleInfo()
    void setSampleInfo(String sampleInfo)
}
class DefaultSampleJarBinarySpec extends DefaultJarBinarySpec implements SampleJarBinarySpec {
    String sampleInfo
    // Required because https://issues.apache.org/jira/browse/GROOVY-7495
    JvmBinaryTasks getTasks() {
        return super.tasks
    }
}

class SampleLibraryRules extends RuleSource {
    @ComponentType
    void register(ComponentTypeBuilder<SampleLibrarySpec> builder) {
        builder.defaultImplementation(DefaultSampleLibrarySpec)
    }

    @BinaryType
    void registerJar(BinaryTypeBuilder<SampleJarBinarySpec> builder) {
        builder.defaultImplementation(DefaultSampleJarBinarySpec)
    }

    @ComponentBinaries
    public void createBinaries(ModelMap<SampleJarBinarySpec> binaries, SampleLibrarySpec library,
                               PlatformResolvers platforms, BinaryNamingSchemeBuilder namingSchemeBuilder,
                               @Path("buildDir") File buildDir, ServiceRegistry serviceRegistry, JavaToolChainRegistry toolChains) {
        def defaultJavaPlatformName = new DefaultJavaPlatform(JavaVersion.current()).name
        def platformRequirement = DefaultPlatformRequirement.create(defaultJavaPlatformName)
        def platform = platforms.resolve(JavaPlatform, platformRequirement)

        def toolChain = toolChains.getForPlatform(platform)
        def binaryName = namingSchemeBuilder.withComponentName(library.name).withTypeString("samplejar").build().lifecycleTaskName
        binaries.create(binaryName) { binary ->
            binary.toolChain = toolChain
            binary.targetPlatform = platform
        }
    }

    @BinaryTasks
    void createTasks(ModelMap<Task> tasks, SampleJarBinarySpec binary) {
        tasks.create("create\${binary.name.capitalize()}", Jar) { jar ->
            jar.from binary.classesDir
            jar.from binary.resourcesDir

            jar.destinationDir = binary.jarFile.parentFile
            jar.archiveName = binary.jarFile.name

            doLast {
                println "Sample binary info: \${binary.sampleInfo}"
            }
        }
    }
}

apply plugin: SampleLibraryRules

model {
    components {
        sampleLib(SampleLibrarySpec) {
            sources {
                lib(JavaSourceSet)
            }
            binaries {
                sampleLibJar {
                    sampleInfo = "Lajos"
                }
            }
        }
    }
}
"""

        expect:
        succeeds "components"
    }
}
