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
import org.gradle.test.fixtures.archive.JarTestFixture

class CustomComponentJarBinariesIntegrationTest extends AbstractIntegrationSpec {
    def setup() {
        buildFile << """
import org.gradle.internal.service.ServiceRegistry
import org.gradle.jvm.internal.*
import org.gradle.jvm.internal.toolchain.*
import org.gradle.jvm.platform.internal.*
import org.gradle.jvm.toolchain.*
import org.gradle.jvm.toolchain.internal.*
import org.gradle.platform.base.internal.*
"""
    }

    def "custom component defined by plugin is built from Java source using JVM component plugin" () {
        given:
        file("src/lib1/java/Lib1.java") << "public class Lib1 {}"
        file("src/lib1/resources/sample.properties") << "origin=lib1"

        file("src/lib2/java/Lib2.java") << "public class Lib2 {}"
        file("src/lib2/resources/sample.properties") << "origin=lib2"

        file("src/sampleLib/lib/Sample.java") << "public class Sample extends Lib1 {}"
        file("src/sampleLib/libResources/sample.properties") << "origin=sample"

        file("src/sampleLib/bin/Bin.java") << "public class Bin extends Lib2 {}"
        file("src/sampleLib/binResources/bin.properties") << "origin=bin"

        // These should not be included in the resulting JAR
        file("src/main/java/Java.java") << "public class Java {}"
        file("src/main/resources/java.properties") << "origin=java"

        buildFile << """
            plugins {
                id 'jvm-component'
                id 'java-lang'
            }
        """
        buildFile << withSampleLibrary()
        buildFile << """
model {
    components {
        lib1(JvmLibrarySpec)
        lib2(JvmLibrarySpec)

        sampleLib(SampleLibrarySpec) {
            sources {
                lib(JavaSourceSet) {
                    dependencies {
                        library "lib1"
                    }
                }
                libResources(JvmResourceSet) {}
            }
            binaries {
                sampleLibJar {
                    sources {
                        bin(JavaSourceSet) {
                            source.srcDir "src/sampleLib/bin"
                            dependencies {
                                library "lib2"
                            }
                        }
                        binResources(JvmResourceSet) {
                            source.srcDir "src/sampleLib/binResources"
                        }
                    }
                }
            }
        }
    }
}
"""

        when:
        succeeds "assemble"

        then:
        new JarTestFixture(file("build/jars/sampleLibJar/sampleLib.jar")).hasDescendants(
            "Sample.class",
            "sample.properties",

            "Bin.class",
            "bin.properties"
        );
    }

    def "custom component defined by plugin is built from Java source without using JVM component plugin" () {
        given:
        file("src/sampleLib/lib/Sample.java") << "public class Sample {}"
        file("src/sampleLib/libResources/sample.properties") << "origin=sample"

        file("src/sampleLib/bin/Bin.java") << "public class Bin {}"
        file("src/sampleLib/binResources/bin.properties") << "origin=bin"

        // These should not be included in the resulting JAR
        file("src/main/java/Java.java") << "public class Java {}"
        file("src/main/resources/java.properties") << "origin=java"

        buildFile << """
            plugins {
                id 'java-lang'
            }
        """
        buildFile << withSampleLibrary()
        buildFile << withSampleJars()
        buildFile << """
model {
    components {
        sampleLib(SampleLibrarySpec) {
            sources {
                lib(JavaSourceSet)
                libResources(JvmResourceSet)
            }
            binaries {
                sampleLibJar {
                    sources {
                        bin(JavaSourceSet) {
                            source.srcDir "src/sampleLib/bin"
                        }
                        binResources(JvmResourceSet) {
                            source.srcDir "src/sampleLib/binResources"
                        }
                    }
                }
            }
        }
    }
}
"""

        when:
        succeeds "assemble"

        then:
        new JarTestFixture(file("build/jars/sampleLibJar/sampleLib.jar")).hasDescendants(
            "Sample.class",
            "sample.properties",

            "Bin.class",
            "bin.properties"
        );
    }

    def withSampleLibrary() {
        return """
interface SampleLibrarySpec extends ComponentSpec {}

class DefaultSampleLibrarySpec extends BaseComponentSpec implements SampleLibrarySpec {}

class SampleLibraryRules extends RuleSource {
    @ComponentType
    void register(ComponentTypeBuilder<SampleLibrarySpec> builder) {
        builder.defaultImplementation(DefaultSampleLibrarySpec)
    }

    @ComponentBinaries
    public void createBinaries(ModelMap<JarBinarySpec> binaries, SampleLibrarySpec library,
                               PlatformResolvers platforms, BinaryNamingSchemeBuilder namingSchemeBuilder,
                               @Path("buildDir") File buildDir, ServiceRegistry serviceRegistry, JavaToolChainRegistry toolChains) {
        def defaultJavaPlatformName = new DefaultJavaPlatform(JavaVersion.current()).name
        def platformRequirement = DefaultPlatformRequirement.create(defaultJavaPlatformName)
        def platform = platforms.resolve(JavaPlatform, platformRequirement)

        def toolChain = toolChains.getForPlatform(platform)
        def binaryName = namingSchemeBuilder.withComponentName(library.name).withTypeString("jar").build().lifecycleTaskName
        binaries.create(binaryName) { binary ->
            binary.toolChain = toolChain
            binary.targetPlatform = platform
        }
    }
}

apply plugin: SampleLibraryRules
"""
    }

    def withSampleJars() {
        """
class SampleJarRules extends RuleSource {

    @BinaryType
    void registerJar(BinaryTypeBuilder<JarBinarySpec> builder) {
        builder.defaultImplementation(DefaultJarBinarySpec)
    }

    @Model
    BinaryNamingSchemeBuilder binaryNamingSchemeBuilder() {
        return new DefaultBinaryNamingSchemeBuilder()
    }

    @Model
    JavaToolChainRegistry javaToolChain(ServiceRegistry serviceRegistry) {
        JavaToolChainInternal toolChain = serviceRegistry.get(JavaToolChainInternal)
        return new DefaultJavaToolChainRegistry(toolChain)
    }

    @Mutate
    public void registerPlatformResolver(PlatformResolvers platformResolvers) {
        platformResolvers.register(new JavaPlatformResolver())
    }

    @Defaults
    void configureJarBinaries(ModelMap<ComponentSpec> libraries, @Path("buildDir") File buildDir) {
        libraries.beforeEach { library ->
            def binariesDir = new File(buildDir, "jars")
            def classesDir = new File(buildDir, "classes")
            library.binaries.withType(JarBinarySpec).beforeEach { jarBinary ->
                jarBinary.baseName = library.name

                def outputDir = new File(classesDir, jarBinary.name)
                jarBinary.classesDir = outputDir
                jarBinary.resourcesDir = outputDir
                jarBinary.jarFile = new File(binariesDir, String.format("%s/%s.jar", jarBinary.name, jarBinary.baseName))
            }
        }
    }

    @BinaryTasks
    void createTasks(ModelMap<Task> tasks, JarBinarySpec binary) {
        tasks.create("create\${binary.name.capitalize()}", Jar) { jar ->
            jar.from binary.classesDir
            jar.from binary.resourcesDir

            jar.destinationDir = binary.jarFile.parentFile
            jar.archiveName = binary.jarFile.name
        }
    }
}

apply plugin: SampleJarRules
"""
    }
}
