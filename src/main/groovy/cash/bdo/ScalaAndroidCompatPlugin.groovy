/*
 * Copyright (C) 2022-present, Chenai Nakam(chenai.nakam@gmail.com)
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

package cash.bdo

import org.example.GreetingToFileTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.ProjectConfigurationException
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.attributes.Usage
import org.gradle.api.component.SoftwareComponentFactory
import org.gradle.api.file.FileCollection
import org.gradle.api.file.SourceDirectorySet
import org.gradle.api.internal.tasks.DefaultScalaSourceSet
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import org.gradle.api.model.ObjectFactory
import org.gradle.api.plugins.JavaBasePlugin
import org.gradle.api.plugins.jvm.internal.JvmPluginServices
import org.gradle.api.plugins.scala.ScalaBasePlugin
import org.gradle.api.plugins.scala.ScalaPlugin
import org.gradle.api.plugins.scala.ScalaPluginExtension
import org.gradle.api.provider.Property
import org.gradle.api.specs.Spec
import org.gradle.api.tasks.ScalaSourceDirectorySet
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.scala.IncrementalCompileOptions
import org.gradle.api.tasks.scala.ScalaCompile
import org.gradle.api.tasks.scala.ScalaDoc

import javax.inject.Inject
import java.util.concurrent.Callable

import static org.gradle.api.attributes.Usage.USAGE_ATTRIBUTE
import static org.gradle.api.internal.lambdas.SerializableLambdas.spec

interface ScalroidExtension {
    static final DEF_MESSAGE = 'Hi, SCALA lover!'
    static final DEF_GREETER = "${ScalaAndroidCompatPlugin.ID_PLUGIN.toUpperCase()} Developer~"

    /** ????????? false????????????kotlin ???????????? scala?????? scala ????????? kotlin???????????????`Task`?????????????????????????????????????????????
     * ??????????????????????????????
     * ???????????????????????? kotlin ?????? scala???scala ????????? kt???????????????????????????????????????
     * 1. Clean ???????????????`./gradlew clean`??????
     * 2. ?????? scala ????????? kt ?????????????????????????????????????????????????????? false??????????????? Sync Now????????????????????????
     * 3. ?????????????????????`./gradlew :app:compile{VARIANT}JavaWithJavac`???????????? app/build/tmp/ ?????? kotlin-classes ??? scala-classes ?????? VARIANT ??????????????????
     * 4. ??????????????????????????? true?????????????????? 2 ??????????????? scala ????????? kt ???????????????????????????
     * 5. ?????????????????????
     *
     * ??????????????? Clean ?????????????????????????????????????????????*/
    Property<Boolean> getScalaCodeReferToKt()
    /** ????????? true????????????????????? scala/kotlin ???????????????????????????*/
    Property<Boolean> getKtCodeReferToScala()

    Property<String> getMessage()

    Property<String> getGreeter()
}

class ScalaAndroidCompatPlugin implements Plugin<Project> {
    // TODO: e.g.
    //  `./gradlew :app:compileGithubDebugJavaWithJavac --stacktrace`
    //  `./gradlew :app:compileGithubDebugJavaWithJavac --info` for LOGGER.info('...')
    //  `./gradlew :app:compileGithubDebugJavaWithJavac --debug`
    protected static final Logger LOGGER = Logging.getLogger(ScalaAndroidCompatPlugin.class)

    static final NAME_PLUGIN = 'scalroid'
    static final NAME_ANDROID_EXTENSION = 'android'
    static final NAME_SCALA_EXTENSION = 'scala'
    static final NAME_KOTLIN_EXTENSION = 'kotlin'
    static final ID_PLUGIN = "cash.bdo.$NAME_PLUGIN"
    static final ID_ANDROID_APP = 'com.android.application'
    static final ID_ANDROID_LIB = 'com.android.library'
    // ???????????????`com.android.build.gradle.api.AndroidBasePlugin`?????????
    static final ID_ANDROID_BASE = 'com.android.base'
    static final ID_KOTLIN_ANDROID = 'org.jetbrains.kotlin.android'
    static final ID_PRE_BUILD = 'preBuild'
    static final ID_TEST_TO_FILE = 'testToFile'

    private void checkArgsProbablyWarning(ScalroidExtension ext) {
        final scalaReferKt = ext.scalaCodeReferToKt.get()
        final ktReferScala = ext.ktCodeReferToScala.get()
        final checked = false
        final lineMsg = "(Maybe error with both `$checked`)"
        if (scalaReferKt == ktReferScala && scalaReferKt == checked) {
            LOGGER.warn "Parameter values may be set incorrectly. Are you sure them?\n" +
                    "android {\n  ${NAME_PLUGIN} {\n" +
                    "    scalaCodeReferToKt = ${scalaReferKt} $lineMsg\n" +
                    "    ktCodeReferToScala = ${ktReferScala} $lineMsg\n" +
                    "  }\n" +
                    "  ..."
        }
    }

    private void testPrintParameters(Project project) {
        LOGGER.warn "applying plugin `$ID_PLUGIN` by ${project}"
        // project.path: :app, project.name: app, project.group: DemoMaterial3, project.version: unspecified
        //LOGGER.info "$NAME_PLUGIN ---> project.path: ${project.path}, project.name: ${project.name}, project.group: ${project.group}, project.version: ${project.version}"
        //LOGGER.info ''
    }
    private final ObjectFactory factory
    private final SoftwareComponentFactory softCptFactory
    private final JvmPluginServices jvmServices

    @Inject
    ScalaAndroidCompatPlugin(ObjectFactory objectFactory, SoftwareComponentFactory softCptFactory, JvmPluginServices jvmServices) {
        this.factory = objectFactory
        this.softCptFactory = softCptFactory
        this.jvmServices = jvmServices
    }

    @Override
    void apply(Project project) {
        testPrintParameters(project)
        if (![ID_ANDROID_APP, ID_ANDROID_LIB, ID_ANDROID_BASE].any { project.plugins.findPlugin(it) }) {
            // apply plugins ??????????????????
            throw new ProjectConfigurationException("Please apply `$ID_ANDROID_APP` or `$ID_ANDROID_LIB` plugin and `$ID_KOTLIN_ANDROID` before applying `$ID_PLUGIN` plugin.", new Throwable())
        }
        if (!project.plugins.findPlugin(ID_KOTLIN_ANDROID)) {
            throw new ProjectConfigurationException("Please apply `$ID_KOTLIN_ANDROID` plugin before applying `$ID_PLUGIN` plugin.", new Throwable())
        }
        ScalroidExtension extension = project.extensions.create(NAME_PLUGIN, ScalroidExtension)

        // 1. ??????`ScalaBasePlugin`???????????? Android ?????????????????????????????????
        project.pluginManager.apply(ScalaBasePlugin) // or apply 'org.gradle.scala-base'
        ScalaPluginExtension scalaExtension = project.extensions.getByName(NAME_SCALA_EXTENSION)
        addScalaPluginExtensionToScalroidClosure(extension, scalaExtension)

        final isLibrary = project.plugins.hasPlugin(ID_ANDROID_LIB)
        // 2. ?????? Scala ????????????????????????????????? Task ??????????????????
        linkScalaAndroidResourcesAndTasks(project, extension, isLibrary)
        // 3. ???????????????????????????`Task`???
        addThisPluginTask(project, extension, scalaExtension, isLibrary)
    }

    private void addThisPluginTask(Project project, ScalroidExtension extension, ScalaPluginExtension scalaExtension, boolean isLibrary) {
        project.task(NAME_PLUGIN) {
            // ??????????????????????????????`build.gradle`?????????
            extension.scalaCodeReferToKt = false
            extension.ktCodeReferToScala = true
            extension.message = ScalroidExtension.DEF_MESSAGE
            extension.greeter = ScalroidExtension.DEF_GREETER
            doLast {
                final version = scalaExtension.zincVersion.get()
                // `extension.convention.plugins.get(NAME_SCALA_EXTENSION).zincVersion.get()`
                assert version == extension.scala.zincVersion.get()
                LOGGER.error "${extension.message.get()} by ${extension.greeter.get()}"
                LOGGER.error "Scala zinc version: $version"
            }
        }
        if (isLibrary) return
        project.tasks.register(ID_TEST_TO_FILE, GreetingToFileTask) {
            destination = project.objects.fileProperty()
            destination.set(project.layout.buildDirectory.file("${NAME_PLUGIN}/test-to-file.txt"))
        }
        project.tasks.getByName(ID_PRE_BUILD) {
            dependsOn NAME_PLUGIN
            //dependsOn ID_TEST_TO_FILE
        }
    }

    private void linkScalaAndroidResourcesAndTasks(Project project, ScalroidExtension scalroid, boolean isLibrary) {
        final androidExtension = project.extensions.getByName(NAME_ANDROID_EXTENSION)
        Plugin androidPlugin = isLibrary ? project.plugins.findPlugin(ID_ANDROID_LIB) : project.plugins.findPlugin(ID_ANDROID_APP)
        addPluginExtensionToAndroidClosure(androidExtension, scalroid)

        //final scalaBasePlugin = project.plugins.findPlugin(ScalaBasePlugin)
        final workDir = project.layout.buildDirectory.file(NAME_PLUGIN).get().asFile
        //project.tasks.getByName(ID_PRE_BUILD).doLast { workDir.mkdirs() }

        // ?????????`project.afterEvaluate`?????????`sourceSet`??????????????????
        // ??????????????????????????????`build.gradle`??????`sourceSets.main.scala.xxx`????????????
        // ??????????????????????????? apply `build.gradle`??????`sourceSets.main.scala.xxx`????????????
        //androidExtension.sourceSets.each { sourceSet -> }
        // ??????????????????`sourceSet.java.srcDirs += sourceSet.scala.srcDirs`???`project.afterEvaluate{}`??????????????????????????????????????????
        // Caused by: org.gradle.internal.typeconversion.UnsupportedNotationException: Cannot convert the provided notation to a File or URI: [/Users/weichou/git/bdo.cash/demo-material-3/app/src/githubDebug/scala].
        // The following types/formats are supported:
        //  - A String or CharSequence path, for example 'src/main/java' or '/usr/include'.  - A String or CharSequence URI, for example 'file:/usr/include'.  - A File instance.  - A Path instance.  - A Directory instance.  - A RegularFile instance.  - A URI or URL instance.  - A TextResource instance.
        // ???????????????????????????
        final sourceSetConfig = { sourceSet -> // androidTest, test, main
            LOGGER.info "$NAME_PLUGIN ---> sourceSetConfig:${sourceSet.name}"
            if (!resolveScalaSrcDirsToAndroidSourceSetsClosure(project, sourceSet, false)) {
                return // ??????????????????`main`??????????????????
            }
            // ??????`DefaultScalaSourceSet`???????????????`scalaSourceDirectorySet.getFilter().include("**/*.java", "**/*.scala")`????????????????????? scala ???????????? java ????????????
            // ???????????????????????? scala ????????? java????????????????????????????????????????????????????????????????????????(????????????????????????????????????????????????)???
            sourceSet.java.srcDirs += sourceSet.scala.srcDirs // com.google.common.collect.SingletonImmutableSet<File> ??? RegularImmutableSet
            // java???scala ????????????????????????????????????`./gradlew :app:assemble`???????????????
            // >D8: Type `xxx.TestJavaXxx` is defined multiple times:
            //sourceSet.java.filter.excludes += "**/scala/**"
            sourceSet.java.filter.exclude { //org.gradle.api.internal.file.AttributeBasedFileVisitDetails it ->
                return it.file.path.contains('/scala/')
            }
            if (sourceSet.java.srcDirs) LOGGER.info "${sourceSet.java.srcDirs} / ${sourceSet.java.srcDirs.class}"
            if (sourceSet.kotlin) {
                final ktSrc = sourceSet.kotlin.srcDirs // com.google.common.collect.RegularImmutableSet<File>
                // ????????????????????????kt ?????? scala ??????????????????
                sourceSet.kotlin.srcDirs += sourceSet.scala.srcDirs // LinkedHashSet<File>
                // ??????????????????????????????????????? scala ?????? java ?????????????????????????????????
                sourceSet.scala.srcDirs += ktSrc
                sourceSet.scala.filter.exclude { // ??????`ktSrc`?????? java ????????????????????????????????????????????? java ????????????????????? scala-classes ???????????????????????? jar ????????? Xxx.class ?????????
                    return it.file.path.contains('/java/')
                }
            }
            LOGGER.info "$NAME_PLUGIN ---> java.srcDirs:" + sourceSet.java.srcDirs
            LOGGER.info "$NAME_PLUGIN ---> scala.srcDirs:" + sourceSet.scala.srcDirs
            LOGGER.info "$NAME_PLUGIN ---> kotlin.srcDirs:" + (sourceSet.kotlin ? sourceSet.kotlin.srcDirs : null)
            LOGGER.info ''
        }
        androidExtension.sourceSets.whenObjectAdded { sourceSet -> // androidTest, main, test
            sourceSetConfig.call(sourceSet)
        }
        // ????????? library ??????whenObjectAdded ????????????
        androidExtension.sourceSets.each { sourceSet -> // ...
            sourceSetConfig.call(sourceSet)
        }
        final mainSourceSet = androidExtension.sourceSets.getByName('main')
        LOGGER.info ''
        LOGGER.info "$NAME_PLUGIN ---> mainSourceSet: $mainSourceSet"
        LOGGER.info ''
        LOGGER.info "|||||||||| |||||||||| |||||||||| |||||||||| |||||||||| AFTER EVALUATE |||||||||| |||||||||| |||||||||| |||||||||| ||||||||||"

        // ?????????`main`???`project.afterEvaluate{}`???????????????????????????????????????
        sourceSetConfig.call(mainSourceSet)

        project.afterEvaluate {
            LOGGER.info ''
            ////////// ////////// ////////// ////////// ////////// ////////// ////////// ////////// ////////// ////////// ////////// ////////// //////////
            //printConfiguration(project, 'implementation')

            //dependencies {
            //    ?????????`implementation`??????`Configuration`??????????????????`Android`?????????????????????
            //    ????????????????????????`dependencies`?????????????????????????????????????????????????????????
            //    implementation "androidx.core:core-ktx:$ktx.Version"
            //    implementation "org.scala-lang:scala-library:$scala2.Version"
            //}
            // ?????????`ScalaCompile`????????????`Scala`?????????????????????`ScalaCompile`???`classpath`???????????????"library"??? jar????????????`scalaRuntime.inferScalaClasspath(compile.getClasspath())`???
            // `classpath`??????`FileCollection`??????`Configuration`?????????`FileCollection`???????????????????????????`Configuration`???
            // ??????????????????`x.register("xxx", ScalaCompile)`???

            ////////// ////////// ////////// ////////// ////////// ////////// ////////// ////////// ////////// ////////// ////////// ////////// //////////
            final variantsAll = androidExtension.testVariants + (isLibrary ? androidExtension.libraryVariants : androidExtension.applicationVariants)
            final variantsNames = new java.util.HashSet<String>()
            final variantsMap = new java.util.HashMap<String, Object>()
            variantsAll.each { variant ->
                variantsNames.add(variant.name)
                variantsMap.put(variant.name, variant)
            }

            final test = 'test'
            final androidTest = 'androidTest'

            LOGGER.info ''
            LOGGER.info "$NAME_PLUGIN ---> variantsNames: ${variantsNames.join(", ")}"
            LOGGER.info ''

            final incrementalAnalysisUsage = factory.named(Usage, "incremental-analysis")
            androidExtension.sourceSets.each { sourceSet -> // androidTest, test, main
                LOGGER.info "$NAME_PLUGIN <<<===>>> sourceSet.name:${sourceSet.name}, sourceSet:$sourceSet"

                ////////// ////////// ////////// ////////// ////////// ////////// ////////// ////////// ////////// ////////// ////////// ////////// //////////
                // ???????????????`sourceSet.name`???`variant.name`???????????????
                // ?????????`sourceSet.name`???`androidTestGithubDebug`??????`variant.name`???`githubDebugAndroidTest`???????????????`compileXxxJava/Kotlin` task ????????????`variant.name`????????????
                // ?????????????????????
                def srcSetNameMatchVariant = sourceSet.name
                LOGGER.info "$NAME_PLUGIN ---> contains:${variantsNames.contains(srcSetNameMatchVariant)}"
                if (srcSetNameMatchVariant != 'main') {
                    if (!variantsNames.contains(srcSetNameMatchVariant)) {
                        if (srcSetNameMatchVariant.startsWith(androidTest)) {
                            if (srcSetNameMatchVariant != androidTest) srcSetNameMatchVariant = srcSetNameMatchVariant.substring(androidTest.length()).uncapitalize() + androidTest.capitalize()
                        } else if (srcSetNameMatchVariant.startsWith(test)) {
                            if (srcSetNameMatchVariant != test) srcSetNameMatchVariant = srcSetNameMatchVariant.substring(test.length()).uncapitalize() + test.capitalize()
                        } else if (srcSetNameMatchVariant.contains(androidTest.capitalize()) || srcSetNameMatchVariant.contains(test.capitalize()) || srcSetNameMatchVariant.contains(androidTest) || srcSetNameMatchVariant.contains(test)) {
                            // ????????????????????????????????????????????????`androidTest`???`test`?????????????????????
                            //LOGGER.info "$NAME_PLUGIN ---> exception:${srcSetNameMatchVariant}"
                            throw new ProjectConfigurationException("sourceSet.name(${sourceSet.name}) not contains in `variants.names` and not within the expected range, please check.", new Throwable())
                        }
                    }
                    LOGGER.info "$NAME_PLUGIN ---> srcSetNameMatchVariant:${srcSetNameMatchVariant}"
                    if (!variantsNames.contains(srcSetNameMatchVariant)) {
                        LOGGER.info "$NAME_PLUGIN ||| return"
                        return
                    }
                }
                final variant = variantsMap[srcSetNameMatchVariant]
                //ScalaRuntime scalaRuntime = project.extensions.getByName(SCALA_RUNTIME_EXTENSION_NAME)
                configureScalaCompile(project, variant, sourceSet, mainSourceSet, androidExtension, srcSetNameMatchVariant, incrementalAnalysisUsage, isLibrary)
            }

            LOGGER.info "||||||||| |||||||||| |||||||||| link all variants depends on |||||||||| |||||||||| ||||||||||"
            variantsAll.each { variant ->
                //variant = "$flavor$buildType" // e.g. "googleplayRelease", "githubDebug".
                //"compile${variant.name.capitalize()}Scala" // `.capitalize()`???????????????
                LOGGER.info ''

                linkScalaCompileDependsOn(project, scalroid, androidPlugin, androidExtension, workDir, variant)

                LOGGER.info "<<<<<<<<<<<============ ${variant.name} DONE ===============>>>>>>>>>>>>>>>>>>>"
            }

            ////////// ////////// ////////// ////////// ////////// ////////// ////////// ////////// ////////// ////////// ////////// ////////// //////////
            // ????????????????????????`linkScalaCompileDependsOn()`??????`ScalaCompile`??????????????????????????????
            // ??????????????????`main`??? variant?????????????????????`linkScalaCompileDependsOn()`?????????????????? variant???
            final possibleVariantName = variantsNames.find {
                !(it.contains(androidTest.capitalize()) || it.contains(test.capitalize()) || it.contains(androidTest) || it.contains(test))
            }
            final possibleVariant = variantsAll.find { it.name == possibleVariantName }
            configureScaladocAndIncrementalAnalysis(project, mainSourceSet, possibleVariant)
        }
    }

    void configureScaladocAndIncrementalAnalysis(Project project, mainSourceSet, possibleVariant) {
        final Configuration incrementalAnalysisElements = project.configurations.getByName("incrementalScalaAnalysisElements")
        final mainScalaTaskName = genScalaCompileTaskName(possibleVariant.name) // mainSourceSet.name
        final TaskProvider<ScalaCompile> compileScala = project.tasks.withType(ScalaCompile).named(mainScalaTaskName)
        final mainScalaCompile = compileScala.get()
        incrementalAnalysisElements.outgoing.artifact(mainScalaCompile.analysisMappingFile) {
            builtBy(compileScala)
        }
        configureScaladoc(project, mainSourceSet, mainScalaCompile)
    }

    // ??? scalroid ????????? android ???????????????????????????
    // android {
    //   scalroid {
    //     message = 'Hi'
    //     greeter = 'Gradle'
    //   }
    // }
    private void addPluginExtensionToAndroidClosure(android, scalroid) {
        android.convention.plugins.put(NAME_PLUGIN, scalroid) // ???????????????
        // ??????????????????`InvokerHelper.invokeMethod()`??????
        // `android.metaClass."get${NAME_PLUGIN.capitalize()}" = scalroid`
        android.metaClass."$NAME_PLUGIN" = scalroid // ???????????????????????????
    }

    //scalroid {
    //    scala.zincVersion = '1.3.5'
    //    ...
    //}
    private void addScalaPluginExtensionToScalroidClosure(scalroid, scala) {
        scalroid.convention.plugins.put(NAME_SCALA_EXTENSION, scala) // ???????????????
        // ??????????????????????????????????????????`build.gradle`?????????????????????????????????
        // `scalroid.scala.zincVersion = xxx`
        // ???????????????
        // scalroid {
        //   scala.zincVersion.set(xxx)
        // }
        scalroid.metaClass."$NAME_SCALA_EXTENSION" = scala
    }

    // [evaluated] ???????????????`project.afterEvaluate{}`????????????
    private boolean resolveScalaSrcDirsToAndroidSourceSetsClosure(Project project, sourceSet, boolean evaluated) {
        LOGGER.info "$NAME_PLUGIN ---> [resolveScalaSrcDirsToAndroidSourceSetsClosure]sourceSet.name:${sourceSet.name}, displayName:${sourceSet.displayName}"
        LOGGER.info "$NAME_PLUGIN ---> [resolveScalaSrcDirsToAndroidSourceSetsClosure]sourceSet.extensions:${sourceSet.extensions}"
        //org.gradle.internal.extensibility.DefaultConvention

        // ???????????????`sourceSet`??????????????????????????????
        if (sourceSet.extensions.findByName("scala")) return false

        final displayName = sourceSet.displayName //(String) InvokerHelper.invokeMethod(sourceSet, "getDisplayName", null)
        //Convention sourceSetConvention = sourceSet.convention //(Convention) InvokerHelper.getProperty(sourceSet, "convention")
        DefaultScalaSourceSet scalaSourceSet = new DefaultScalaSourceSet(displayName, factory)
        final SourceDirectorySet scalaDirSet = scalaSourceSet.scala
        // ??????`?????????convention???`?????????????????????
        // sourceSets { main { scala.srcDirs += ['src/main/java'] } ...}
        sourceSet.convention.plugins.put("scala", scalaSourceSet)
        sourceSet.extensions.add(ScalaSourceDirectorySet, "scala", scalaDirSet)
        scalaDirSet.srcDir(project.file("src/${sourceSet.name}/scala"))

        // Explicitly capture only a FileCollection in the lambda below for compatibility with configuration-cache.
        FileCollection scalaSource = scalaDirSet
        sourceSet.resources.filter.exclude(spec(element -> scalaSource.contains(element.file)))
        return true
    }

    private void configureScalaCompile(Project project, variant, sourceSet, mainSourceSet, androidExtension, srcSetNameMatchVariant, Usage incrementalAnalysisUsage, boolean isLibrary) {
        LOGGER.info "$NAME_PLUGIN ---> [configureScalaCompile]sourceSet.name:${sourceSet.name}, variant:${variant}, srcSetNameMatchVariant:${srcSetNameMatchVariant}"

        final ScalaSourceDirectorySet scalaDirectorySet = sourceSet.extensions.getByType(ScalaSourceDirectorySet)
        LOGGER.info "$NAME_PLUGIN ---> [configureScalaCompile]scalaDirectorySet.name:${scalaDirectorySet.name}, scalaDirectorySet.displayName:${scalaDirectorySet.displayName}"

        ScalaSourceDirectorySet mainScalaDirSet
        Configuration mainClasspathImpl
        Configuration mainClasspathApi
        if (sourceSet != mainSourceSet) {
            mainScalaDirSet = mainSourceSet.extensions.getByType(ScalaSourceDirectorySet)
            // ?????????????????????`project.configurations.implementation`
            mainClasspathImpl = project.configurations.getByName(mainSourceSet.implementationConfigurationName)
            mainClasspathApi = project.configurations.getByName(mainSourceSet.apiConfigurationName)

            LOGGER.info "$NAME_PLUGIN ---> [configureScalaCompile]mainScalaDirSet.name:${mainScalaDirSet.name}, mainScalaDirSet.displayName:${mainScalaDirSet.displayName}"
            LOGGER.info ''
        }

        Configuration classpathBySourceSetImpl = project.configurations.getByName(sourceSet.implementationConfigurationName)
        LOGGER.info "$NAME_PLUGIN ---> [configureScalaCompile]sourceSet.implementationConfigurationName:${sourceSet.implementationConfigurationName}"
        Configuration classpathBySourceSetApi = project.configurations.getByName(sourceSet.apiConfigurationName)
        LOGGER.info "$NAME_PLUGIN ---> [configureScalaCompile]sourceSet.apiConfigurationName:${sourceSet.apiConfigurationName}"
        //printConfiguration(project, sourceSet.implementationConfigurationName)
        //printConfiguration(project, sourceSet.apiConfigurationName)

        // ???????????????????????? compileOnly, runtimeOnly, ...
        final classpathConfigs = [mainClasspathImpl, mainClasspathApi, classpathBySourceSetImpl, classpathBySourceSetApi]
        classpathConfigs.removeIf { !it || it.dependencies.isEmpty() }
        LOGGER.info "$NAME_PLUGIN ---> [configureScalaCompile]sourceSet.name:${sourceSet.name}, classpathConfigs.size:${classpathConfigs.size()}"

        Configuration incrementalAnalysis = project.configurations.create("incrementalScalaAnalysisFor${srcSetNameMatchVariant.capitalize()}")
        incrementalAnalysis.description = "Incremental compilation analysis files for ${sourceSet.displayName}"
        incrementalAnalysis.visible = false
        incrementalAnalysis.canBeResolved = true
        incrementalAnalysis.canBeConsumed = false
        incrementalAnalysis.extendsFrom = classpathConfigs
        incrementalAnalysis.attributes.attribute(USAGE_ATTRIBUTE, incrementalAnalysisUsage)

        project.tasks.register(genScalaCompileTaskName(srcSetNameMatchVariant), ScalaCompile) { ScalaCompile scalaCompile ->
            println "-------------------------------------------------------------------------------------------"
            println "$NAME_PLUGIN ---> [configureScalaCompile]compileTaskName:${scalaCompile.name}, variant:${variant ? variant.name : null}"

            final compilerClasspathName = "${srcSetNameMatchVariant}ScalaCompileClasspath"
            //scalaCompile.classpath = project.configurations.create("scalaBaseClasspathFor${srcSetNameMatchVariant.capitalize()}")
            //        .extendsFrom(project.configurations.implementation, classpathBySourceSet)
            Configuration compilerClasspath = project.configurations.create(compilerClasspathName)
            compilerClasspath.description = "ScalaCompile classpath for ${sourceSet.displayName}"
            compilerClasspath.visible = false
            compilerClasspath.extendsFrom = classpathConfigs
            if (false) {
//            if (!isLibrary && variant) { // ???????????? main ??? variant???
                final ignoreAttrs = ['version', '.ProductFlavor', '.VariantAttr']
                final forceAttrs = ['artifactType', '.VariantAttr', '.BuildTypeAttr'/*debug/release*/, 'org.gradle.category'/*library*/, 'org.gradle.usage'/*java-runtime*/, '.jvm.environment'/*android*/, '.platform.type'/*androidJvm*/]
                final compilation = kotlinJvmAndroidCompilation(project, variant)
                compilation.relatedConfigurationNames.each { name ->
                    println "$NAME_PLUGIN ---> [configureScalaCompile]relatedConfigurationName:$name"
                    if (name.startsWith(srcSetNameMatchVariant) && name.endsWith("ApiElements")) {
                        println "$NAME_PLUGIN ---> [configureScalaCompile]                   --------> $name"
                        Configuration config = project.configurations.findByName(name)
                        if (config) {
                            config.attributes.each { attrs ->
                                attrs.keySet().each { attr ->
                                    final value = attrs.getAttribute(attr)
                                    println "${attr.name} -> ${value} / ${value.class}"
                                    if (compilerClasspath.attributes.contains(attr)) {
                                        final valueOld = compilerClasspath.attributes.getAttribute(attr)
                                        if (value != valueOld) {
                                            LOGGER.warn "$NAME_PLUGIN ---> [configureScalaCompile]configuring `$compilerClasspathName`, old attribute value fund: ${valueOld}."
                                        }
                                    } else //if (!ignoreAttrs.any { attr.name.contains(it) }) {
                                    if (forceAttrs.any { attr.name.contains(it) }) {
                                        if (attr.name.contains('.VariantAttr')) {
                                            compilerClasspath.attributes.attribute(attr, value.startsWith('debug') ? 'debug' : 'release')
                                        } else compilerClasspath.attributes.attribute(attr, value)
                                    }
                                }
                            }
                        }
                    }
                }
                compilerClasspath.attributes.keySet().each { attr ->
                    println "$NAME_PLUGIN ---> [configureScalaCompile]classpath.attributes:${attr.name}"
                }
                println "-------------------------------------------------------------------------------------------"
//            compilerClasspath.attributes.attribute(BuildTypeAttr.ATTRIBUTE, factory.named(BuildTypeAttr, variant.buildType.name))
            }
            scalaCompile.classpath = compilerClasspath
            // TODO: ????????????`android.jar`????????? classpath??????????????? scala ????????????????????????????????????`android.app.Activity`????????????????????????
            // [Error] /Users/.../demo-material-3/app/src/main/scala/com/example/demomaterial3/Test2.scala:9:7: Class android.app.Activity not found - continuing with a stub. one error found...
            scalaCompile.classpath += androidExtension.bootClasspathConfig.mockableJarArtifact // mock `android.jar`

            ////////// ////////// ////////// ////////// ////////// ////////// ////////// ////////// ////////// ////////// ////////// ////////// //////////
            scalaCompile.source = scalaDirectorySet
            // TODO: ???????????????????????????`./gradlew :app:compileGithubDebugScala --info`??????
            //  Skipping task ':app:compileGithubDebugScala' as it has no source files and no previous output files.
            if (mainScalaDirSet) scalaCompile.source += mainScalaDirSet
            ////////// ////////// ////////// ////////// ////////// ////////// ////////// ////////// ////////// ////////// ////////// ////////// //////////

            scalaCompile.description = "Compiles the ${scalaDirectorySet}."
            //scalaCompile.javaLauncher.convention(getToolchainTool(project, JavaToolchainService::launcherFor))
            scalaCompile.analysisMappingFile.set(project.layout.buildDirectory.file("scala/compilerAnalysis/${scalaCompile.name}.mapping"))

            // Cannot compute at task execution time because we need association with source set
            IncrementalCompileOptions incrementalOptions = scalaCompile.scalaCompileOptions.incrementalOptions
            incrementalOptions.analysisFile.set(project.layout.buildDirectory.file("scala/compilerAnalysis/${scalaCompile.name}.analysis"))
            incrementalOptions.classfileBackupDir.set(project.layout.buildDirectory.file("scala/classfileBackup/${scalaCompile.name}.bak"))

            scalaCompile.analysisFiles.from(incrementalAnalysis.incoming.artifactView({
                lenient(true)
                componentFilter(new Spec<ComponentIdentifier>() {
                    @Override
                    boolean isSatisfiedBy(ComponentIdentifier element) {
                        return element instanceof ProjectComponentIdentifier
                    }
                })
            }).files)
            scalaCompile.dependsOn(scalaCompile.analysisFiles)

            // ????????? kotlin ???????????????????????????????????????????????????????????????
            //scalaDirectorySet.destinationDirectory.convention(project.layout.buildDirectory.dir("tmp/scala-classes/${srcSetNameMatchVariant}"))
            scalaCompile.destinationDirectory.convention(/*scalaDirectorySet.destinationDirectory*/ project.layout.buildDirectory.dir("tmp/scala-classes/${srcSetNameMatchVariant}"))
        }
    }

    private void linkScalaCompileDependsOn(Project project, ScalroidExtension scalroid, Plugin androidPlugin, final androidExtension, final workDir, final variant) {
        //LOGGER.info "$NAME_PLUGIN ---> [linkScalaCompileDependsOn]androidPlugin:${androidPlugin}" // com.android.build.gradle.AppPlugin@8ab260a
        //LOGGER.info "$NAME_PLUGIN ---> [linkScalaCompileDependsOn]workDir:${workDir.path}" // /Users/{PATH-TO-}/demo-material-3/app/build/scalroid
        LOGGER.info "$NAME_PLUGIN ---> [linkScalaCompileDependsOn]variant:${variant.name}" // githubDebug

        final javaTaskName = genJavaCompileTaskName(variant.name)
        final scalaTaskName = genScalaCompileTaskName(variant.name)
        final kotlinTaskName = genKotlinCompileTaskName(variant.name)

        final JavaCompile javaCompile = project.tasks.findByName(javaTaskName)
        if (javaCompile) {
            // ???????????????????????????`scalaCompileTask`??????`project.tasks.register()`?????????
            project.tasks.withType(ScalaCompile).getByName(scalaTaskName) { ScalaCompile scalaCompile ->
                LOGGER.info "$NAME_PLUGIN ---> [linkScalaCompileDependsOn]javaCompile.destinationDirectory:${javaCompile.destinationDirectory.orNull}"

                javaCompile.dependsOn scalaCompile

                // ????????? kotlin ????????????????????????????????????????????????
                //scalaCompile.destinationDirectory.set(project.layout.buildDirectory.dir("tmp/scala-classes/${variant.name}"))
                scalaCompile.sourceCompatibility = javaCompile.sourceCompatibility
                scalaCompile.targetCompatibility = javaCompile.targetCompatibility
                // Unexpected javac output: ??????: [options] ?????? -source 8 ???????????????????????????
                scalaCompile.options.bootstrapClasspath = javaCompile.options.bootstrapClasspath
                scalaCompile.options.encoding = javaCompile.options.encoding
                //scalaCompile.scalaCompileOptions.encoding = scalaCompile.options.encoding

                final kotlinCompile = project.tasks.findByName(kotlinTaskName)
                if (kotlinCompile) {
                    //LOGGER.info "$NAME_PLUGIN ---> [linkScalaCompileDependsOn]kotlinCompile:${kotlinCompile} / ${kotlinCompile.class}" // org.jetbrains.kotlin.gradle.tasks.KotlinCompile

                    wireScalaTasks(project, scalroid, variant, project.tasks.named(scalaTaskName), project.tasks.named(javaTaskName), project.tasks.named(kotlinTaskName))
                } else {
                    LOGGER.warn "$NAME_PLUGIN ---> [linkScalaCompileDependsOn]kotlinCompile:null"
                }
            }
        } else {
            LOGGER.warn "$NAME_PLUGIN ---> [linkScalaCompileDependsOn]javaCompile:null"
        }
    }

    private defaultSourceSet(Project project, variant) {
        // ???????????? variant ????????? SourceSet???
        // debugAndroidTest, debug, release
        // githubDebugAndroidTest, googleplayDebugAndroidTest, githubDebug, googleplayDebug, githubRelease, googleplayRelease
        return kotlinJvmAndroidCompilation(project, variant).defaultSourceSet
    }

    private kotlinAndroidTarget(Project project) {
        // ??????`org.jetbrains.kotlin.gradle.plugin.KotlinAndroidPlugin`?????????`(project.kotlinExtension as KotlinAndroidProjectExtension).target = it`
        // ?????????????????????????????????????????????`.castIsolatedKotlinPluginClassLoaderAware()`????????????????????????????????????
        // ??????????????????????????????????????????????????????`IsolatedKotlinClasspathClassCastException`??????
        final kotlinExtension = project.extensions.getByName(NAME_KOTLIN_EXTENSION) // KotlinAndroidProjectExtension_Decorated
        return kotlinExtension.target // KotlinAndroidTarget
    }

    private kotlinJvmAndroidCompilation(Project project, variant) {
        return kotlinAndroidTarget(project).compilations.getByName(variant.name) // KotlinJvmAndroidCompilation
    }

    private void wireScalaTasks(Project project, ScalroidExtension scalroid, variant, TaskProvider scalaTask, TaskProvider javaTask, TaskProvider kotlinTask) {
        final compilation = kotlinJvmAndroidCompilation(project, variant)
        final outputs = compilation.output.classesDirs // ConfigurableFileCollection
        //LOGGER.info "$NAME_PLUGIN ---> [wireScalaTasks]outputs:${outputs.class}, it.files:${outputs.files}"
        outputs.from(scalaTask.flatMap { it.destinationDirectory })
        //final outputs1 = compilation.output.classesDirs
        //LOGGER.info "$NAME_PLUGIN ---> [wireScalaTasks]outputs1:${outputs1.class}, it.files:${outputs1.files}"

        // ????????????`org.jetbrains.kotlin.gradle.plugin.Android25ProjectHandler`???`wireKotlinTasks()`???
        // ???????????????`project.files(scalaTask.get().destinationDirectory)`????????? Task ??????????????????
        final javaOuts = project.files(project.provider([call: { javaTask.get().destinationDirectory.get().asFile }] as Callable))
        final scalaOuts = project.files(project.provider([call: { scalaTask.get().destinationDirectory.get().asFile }] as Callable))
        final kotlinOuts = project.files(project.provider([call: { kotlinTask.get().destinationDirectory.get().asFile }] as Callable))
        LOGGER.info "$NAME_PLUGIN ---> [wireScalaTasks]javaOuts:${javaOuts}, it.files:${javaOuts.files}"
        LOGGER.info "$NAME_PLUGIN ---> [wireScalaTasks]scalaOuts:${scalaOuts}, it.files:${scalaOuts.files}"
        LOGGER.info "$NAME_PLUGIN ---> [wireScalaTasks]kotlinOuts:${kotlinOuts}, it.files:${kotlinOuts.files}"

        // ??????????????????????????????????????????????????????In order to properly wire up tasks????????????`Object registerPreJavacGeneratedBytecode(FileCollection)`?????????
        // ?????????????????????????????????????????????????????????
//        scalaOuts.builtBy(scalaTask)
        //variant.registerJavaGeneratingTask(scalaTask, scalaTask.get().source.files)
        //variant.registerJavaGeneratingTask(kotlinTask, kotlinTask.get().sources.files)

        //final outsNew = javaOuts + kotlinOuts //.from(kotlinOuts)
        //final classpathKey = variant.registerPreJavacGeneratedBytecode(outsNew)
        //LOGGER.info "$NAME_PLUGIN ---> [wireScalaTasks]classpathKey:${classpathKey} / ${classpathKey.class}" // java.lang.Object@2faa3212 / class java.lang.Object

        // ?????????????????????????????????`classpathKey`?????????????????????null??????
        // ???????????????????????????`compileClasspath`??????????????????????????????`???/??????`????????????????????????
        // ?????????????????????????????????????????????`variant.getGeneratedBytecode(classpathKey)`???????????????????????????
        // ??????`classpathKey`????????????`variant.registerPreJavacGeneratedBytecode(fileCol)`?????????????????????`fileCol`??????????????????
        // ???????????????????????????????????????????????????????????????[??????????????????]??????????????????????????????`FileCollection`???
        // ?????????????????????????????????????????????????????????
//        final compileClasspath = variant.getCompileClasspath(/*classpathKey*/)
        // org.gradle.api.internal.file.collections.DefaultConfigurableFileCollection
        //LOGGER.info "$NAME_PLUGIN ---> [wireScalaTasks]variant.getCompileClasspath(classpathKey):${compileClasspath} / ${compileClasspath.class}"
        // TODO:
        //  java.lang.RuntimeException: Configuration 'githubDebugCompileClasspath' was resolved during configuration time.
        //  This is a build performance and scalability issue.
        //  scalroid ---> [wireScalaTasks]variant.getCompileClasspath(classpathKey):[/Users/weichou/git/bdo.cash/demo-material-3/app/build/intermediates/compile_and_runtime_not_namespaced_r_class_jar/
        //    githubDebug/R.jar, /Users/weichou/.gradle/caches/transforms-3/893b9b5a0019ab2d13f957bcf2fabcb9/transformed/viewbinding-7.3.1-api.jar, /Users/weichou/.gradle/caches/transforms-3/3b9fa4710e9e16ecd783cc23f2a62967/transformed/navigation-ui-ktx-2.5.0-api.jar, /Users/weichou/.gradle/caches/transforms-3/b4c5cd6f5d69a09c90e3ea53830c48f5/transformed/navigation-ui-2.5.0-api.jar, /Users/weichou/.gradle/caches/transforms-3/7c5ae5c220196941122f9db4d7a639bc/transformed/material-1.7.0-api.jar, /Users/weichou/.gradle/caches/transforms-3/a2622ad63284a4fbebfb584b9b851884/transformed/appcompat-1.5.0-api.jar, /Users/weichou/.gradle/caches/transforms-3/6c3948d45ddaf709ba26745189eab999/transformed/viewpager2-1.0.0-api.jar, /Users/weichou/.gradle/caches/transforms-3/2a8d540a7e825c31c133e1550351db89/transformed/navigation-fragment-ktx-2.5.0-api.jar, /Users/weichou/.gradle/caches/transforms-3/d9907136fb1ec2be0470c0c515d25b44/transformed/navigation-fragment-2.5.0-api.jar, /Users/weichou/.gradle/caches/transforms-3/6eaf265bb3918c4fd1dce0869a434f72/transformed/fragment-ktx-1.5.0-api.jar, /Users/weichou/.gradle/caches/transforms-3/3ff5ce784625b6b2d8a5c8ed94aa647c/transformed/fragment-1.5.0-api.jar, /Users/weichou/.gradle/caches/transforms-3/27c0c7f932da50fcd00c251ed8922e6d/transformed/navigation-runtime-ktx-2.5.0-api.jar, /Users/weichou/.gradle/caches/transforms-3/b010a8c66f1d64f1d21c27bb1beb821c/transformed/navigation-runtime-2.5.0-api.jar, /Users/weichou/.gradle/caches/transforms-3/f151321734e20d7116485bb3ab462df6/transformed/activity-ktx-1.5.0-api.jar, /Users/weichou/.gradle/caches/transforms-3/21307ac95e78f6bab6ef32fc6c8c8597/transformed/activity-1.5.0-api.jar, /Users/weichou/.gradle/caches/transforms-3/b7a04d7ac46f005b9d948413fa63b076/transformed/navigation-common-ktx-2.5.0-api.jar, /Users/weichou/.gradle/caches/transforms-3/7a98affa0f1b253f31ac3690fb7b577b/transformed/navigation-common-2.5.0-api.jar, /Users/weichou/.gradle/caches/transforms-3/49780de7b782ff560647221ad2cc9d58/transformed/lifecycle-viewmodel-savedstate-2.5.0-api.jar, /Users/weichou/.gradle/caches/transforms-3/ec53663f906b415395ef3a78e7add5aa/transformed/lifecycle-viewmodel-ktx-2.5.0-api.jar, /Users/weichou/.gradle/caches/transforms-3/498d61dce25ec5c15c1a4140b70f1b13/transformed/lifecycle-runtime-ktx-2.5.0-api.jar, /Users/weichou/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.6.1/97fd74ccf54a863d221956ffcd21835e168e2aaa/kotlinx-coroutines-core-jvm-1.6.1.jar, /Users/weichou/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlinx/kotlinx-coroutines-android/1.6.1/4e61fcdcc508cbaa37c4a284a50205d7c7767e37/kotlinx-coroutines-android-1.6.1.jar, /Users/weichou/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib-jdk8/1.7.20/eac6656981d9d7156e838467d2d8d79093b1570/kotlin-stdlib-jdk8-1.7.20.jar, /Users/weichou/.gradle/caches/transforms-3/394f4ba5a7303202a56e467034c8c851/transformed/core-ktx-1.9.0-api.jar, /Users/weichou/.gradle/caches/transforms-3/6e9fde4cc1497ecd85ffdf980a60e5ab/transformed/appcompat-resources-1.5.0-api.jar, /Users/weichou/.gradle/caches/transforms-3/aa25fefefb4284675752d1b8716a8bf4/transformed/drawerlayout-1.1.1-api.jar, /Users/weichou/.gradle/caches/transforms-3/6f5d72cc112503558a75fc45f0fbfe22/transformed/coordinatorlayout-1.1.0-api.jar, /Users/weichou/.gradle/caches/transforms-3/f66c58055f09c54df80d3ac39cfc8ed7/transformed/dynamicanimation-1.0.0-api.jar, /Users/weichou/.gradle/caches/transforms-3/76fb2fc3976e6e2590393baaa768ea01/transformed/recyclerview-1.1.0-api.jar, /Users/weichou/.gradle/caches/transforms-3/d5505f9d57d8c6a72c4bfbea8e0d1d59/transformed/transition-1.4.1-api.jar, /Users/weichou/.gradle/caches/transforms-3/cb376832b44347e0e2863a0efbfb46c1/transformed/vectordrawable-animated-1.1.0-api.jar, /Users/weichou/.gradle/caches/transforms-3/51c160350e1db060225cf076555bddc5/transformed/vectordrawable-1.1.0-api.jar, /Users/weichou/.gradle/caches/transforms-3/b437c042a4fb25b5473beb3aa3810946/transformed/viewpager-1.0.0-api.jar, /Users/weichou/.gradle/caches/transforms-3/c711a0ee0692c4fae81cdd671dadbde0/transformed/slidingpanelayout-1.2.0-api.jar, /Users/weichou/.gradle/caches/transforms-3/60367c0dc3b93ae22d44974ed438a5f5/transformed/customview-1.1.0-api.jar, /Users/weichou/.gradle/caches/transforms-3/05f2d7edda9b38183e0acbcdee061d41/transformed/legacy-support-core-utils-1.0.0-api.jar, /Users/weichou/.gradle/caches/transforms-3/5bb317b860d652a7e95da35400298e99/transformed/loader-1.0.0-api.jar, /Users/weichou/.gradle/caches/transforms-3/0c3f5120a16030c3b02e209277f606e0/transformed/core-1.9.0-api.jar, /Users/weichou/.gradle/caches/transforms-3/c9dedd1f96993d0760d55df081a29879/transformed/cursoradapter-1.0.0-api.jar, /Users/weichou/.gradle/caches/transforms-3/8bb096c8444b515fe520f2a8e48caab1/transformed/savedstate-ktx-1.2.0-api.jar, /Users/weichou/.gradle/caches/transforms-3/cdb42f31a8e9bad7a8ea34dbb5e7b7f6/transformed/savedstate-1.2.0-api.jar, /Users/weichou/.gradle/caches/transforms-3/9c4ff74ae88f4d922542d59f88faa6bc/transformed/cardview-1.0.0-api.jar, /Users/weichou/.gradle/caches/transforms-3/ee8da9fe5cd86ed12e7a623b8098a166/transformed/lifecycle-runtime-2.5.0-api.jar, /Users/weichou/.gradle/caches/transforms-3/b81c0a66f40e81ce8db2df92a1963d5b/transformed/versionedparcelable-1.1.1-api.jar, /Users/weichou/.gradle/caches/transforms-3/83b5d525a0ebd9bc00322953f05c96f4/transformed/lifecycle-viewmodel-2.5.0-api.jar, /Users/weichou/.gradle/caches/modules-2/files-2.1/androidx.collection/collection-ktx/1.1.0/f807b2f366f7b75142a67d2f3c10031065b5168/collection-ktx-1.1.0.jar, /Users/weichou/.gradle/caches/modules-2/files-2.1/androidx.collection/collection/1.1.0/1f27220b47669781457de0d600849a5de0e89909/collection-1.1.0.jar, /Users/weichou/.gradle/caches/transforms-3/f306f739dc2177bf68711c65fe4e11e2/transformed/lifecycle-livedata-2.0.0-api.jar, /Users/weichou/.gradle/caches/transforms-3/dfab4e028be7115b8ccc3796fb2e0428/transformed/core-runtime-2.1.0-api.jar, /Users/weichou/.gradle/caches/modules-2/files-2.1/androidx.arch.core/core-common/2.1.0/b3152fc64428c9354344bd89848ecddc09b6f07e/core-common-2.1.0.jar, /Users/weichou/.gradle/caches/transforms-3/82360dce7c90d10221f06f4ddfa5bc67/transformed/lifecycle-livedata-core-ktx-2.5.0-api.jar, /Users/weichou/.gradle/caches/transforms-3/8d444fd9988175cecd13055f04813b91/transformed/lifecycle-livedata-core-2.5.0-api.jar, /Users/weichou/.gradle/caches/modules-2/files-2.1/androidx.lifecycle/lifecycle-common/2.5.0/1fdb7349701e9cf2f0a69fc10642b6fef6bb3e12/lifecycle-common-2.5.0.jar, /Users/weichou/.gradle/caches/transforms-3/afa292a87f73b3eaac7c83ceefd92574/transformed/interpolator-1.0.0-api.jar, /Users/weichou/.gradle/caches/transforms-3/a7341bbf557f0eee7488093003db0f01/transformed/documentfile-1.0.0-api.jar, /Users/weichou/.gradle/caches/transforms-3/46ecd33fc8b0aff76d637a8fc3226518/transformed/localbroadcastmanager-1.0.0-api.jar, /Users/weichou/.gradle/caches/transforms-3/2e9cbd427ac36e8b9e40572d70105d5c/transformed/print-1.0.0-api.jar, /Users/weichou/.gradle/caches/modules-2/files-2.1/androidx.annotation/annotation/1.3.0/21f49f5f9b85fc49de712539f79123119740595/annotation-1.3.0.jar, /Users/weichou/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib-jdk7/1.7.20/2a729aa8763306368e665e2b747abd1dfd29b9d5/kotlin-stdlib-jdk7-1.7.20.jar, /Users/weichou/.gradle/caches/transforms-3/f45fdb3a6f32f3117a02913a5475531b/transformed/annotation-experimental-1.3.0-api.jar, /Users/weichou/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/1.7.20/726594ea9ba2beb2ee113647fefa9a10f9fabe52/kotlin-stdlib-1.7.20.jar, /Users/weichou/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib-common/1.7.20/e15351bdaf9fa06f009be5da7a202e4184f00ae3/kotlin-stdlib-common-1.7.20.jar, /Users/weichou/.gradle/caches/modules-2/files-2.1/org.jetbrains/annotations/13.0/919f0dfe192fb4e063e7dacadee7f8bb9a2672a9/annotations-13.0.jar, /Users/weichou/.gradle/caches/transforms-3/b61a19ce0ffb6b0eab4a6ede97932e2f/transformed/constraintlayout-2.1.4-api.jar, /Users/weichou/.gradle/caches/transforms-3/632e82547c61849d331ad6e31a1fb88c/transformed/annoid-af2b53cfce-api.jar, /Users/weichou/.gradle/caches/modules-2/files-2.1/com.github.dedge-space/scala-lang/253dc64cf9/51c97f073e45e5183af054e4596869d035f47b2d/scala-lang-253dc64cf9.jar, /Users/weichou/.gradle/caches/modules-2/files-2.1/org.scala-lang/scala-compiler/2.11.12/a1b5e58fd80cb1edc1413e904a346bfdb3a88333/scala-compiler-2.11.12.jar, /Users/weichou/.gradle/caches/modules-2/files-2.1/org.scala-lang/scala-reflect/2.11.12/2bb23c13c527566d9828107ca4108be2a2c06f01/scala-reflect-2.11.12.jar, /Users/weichou/.gradle/caches/modules-2/files-2.1/org.scala-lang.modules/scala-xml_2.11/1.0.5/77ac9be4033768cf03cc04fbd1fc5e5711de2459/scala-xml_2.11-1.0.5.jar, /Users/weichou/.gradle/caches/modules-2/files-2.1/org.scala-lang.modules/scala-parser-combinators_2.11/1.0.4/7369d653bcfa95d321994660477a4d7e81d7f490/scala-parser-combinators_2.11-1.0.4.jar, /Users/weichou/.gradle/caches/modules-2/files-2.1/org.scala-lang/scala-library/2.12.17/4a4dee1ebb59ed1dbce014223c7c42612e4cddde/scala-library-2.12.17.jar, /Users/weichou/.gradle/caches/modules-2/files-2.1/com.github.dedge-space/annoguard/v1.0.5-beta/d9f31382b1d2d4bbf8e34de4b7ef6a547277cfdb/annoguard-v1.0.5-beta.jar, /Users/weichou/.gradle/caches/modules-2/files-2.1/com.google.code.gson/gson/2.8.0/c4ba5371a29ac9b2ad6129b1d39ea38750043eff/gson-2.8.0.jar, /Users/weichou/git/bdo.cash/demo-material-3/app/build/tmp/kotlin-classes/githubDebug]
        //LOGGER.info "$NAME_PLUGIN ---> [wireScalaTasks]variant.getCompileClasspath(classpathKey):${compileClasspath.files}"
//        scalaTask.configure { scala -> // ...
//            scala.classpath += compileClasspath
//        }
        // ???????????????????????????????????????
        //final prevRegistered = variant.getGeneratedBytecode()
        // ???????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????? compileClasspath?????????????????????
//        final clzPathKey = variant.registerPreJavacGeneratedBytecode(scalaOuts)
//        kotlinTask.configure { kt -> // `kt.classpath`???`kt.libraries`????????????
        //final files = kt.libraries as ConfigurableFileCollection
        //files.from(variant.getGeneratedBytecode() - prevRegistered) //variant.getCompileClasspath(clzPathKey) - files)
//            kt.libraries.from(scalaOuts) // ????????????????????????????????????????????????

        /*if (!kt.incremental) {
            LOGGER.info "$NAME_PLUGIN ---> [wireScalaTasks]variant:${variant.name}, kt.incremental:${kt.incremental}, change to true."
            //kt.incremental = true // ???????????????????????????????????????????????????
        }*/
        // ?????????property 'classpathSnapshotProperties.useClasspathSnapshot' cannot be changed any further.
        // ?????????????????????`kt.classpathSnapshotProperties`????????????????????????????????????`task.classpathSnapshotProperties.classpathSnapshot.from(xxx).disallowChanges()`???
        // TODO: ?????????????????????????????? gradle.properties ??????????????????
        //  `kotlin.incremental.useClasspathSnapshot=true`
        //  ????????????????????? local.properties ????????????
        //  ??????????????????????????? scala-kotlin ???????????????https://blog.jetbrains.com/zh-hans/kotlin/2022/07/a-new-approach-to-incremental-compilation-in-kotlin/
        //kt.classpathSnapshotProperties.classpathSnapshot.from(scalaOuts)
        /*if (!kt.classpathSnapshotProperties.useClasspathSnapshot.get()) {
            LOGGER.info "$NAME_PLUGIN ---> [wireScalaTasks]variant:${variant.name}, useClasspathSnapshot:${kt.classpathSnapshotProperties.useClasspathSnapshot.get()}, change to true is disallow."
            //kt.classpathSnapshotProperties.useClasspathSnapshot.set(true)
        }*/
//        }

        // TODO: ????????????????????????
        LOGGER.info "$NAME_PLUGIN ---> [wireScalaTasks]scalaCodeReferToKt:${scalroid.scalaCodeReferToKt.get()}, ktCodeReferToScala:${scalroid.ktCodeReferToScala.get()}"
        checkArgsProbablyWarning(scalroid)

        final classpathKey = variant.registerPreJavacGeneratedBytecode(scalaOuts)
        if (scalroid.scalaCodeReferToKt.get()) {
            scalaTask.configure { scala -> // ...
                scala.classpath += variant.getCompileClasspath(classpathKey)
            }
            // ???????????????- Gradle detected a problem with the following location: '/Users/weichou/git/bdo.cash/demo-material-3/app/build/tmp/scala-classes/githubDebug'.
            // Reason: Task ':app:mergeGithubDebugJavaResource' uses this output of task ':app:compileGithubDebugScala' without declaring an explicit or implicit dependency. This can lead to incorrect results being produced, depending on what order the tasks are executed. Please refer to https://docs.gradle.org/7.4/userguide/validation_problems.html#implicit_dependency for more details about this problem.
            final mergeJavaRes = project.tasks.findByName(genMergeJavaResourceTaskName(variant.name))
            if (mergeJavaRes) mergeJavaRes.dependsOn scalaTask
        } else {
            scalaOuts.builtBy(scalaTask)
        }
        if (scalroid.ktCodeReferToScala.get()) {
            kotlinTask.configure { kt -> // ...
                kt.libraries.from(scalaOuts)
            }
        }
    }

    ////////// ////////// ////////// ////////// ////////// ////////// ////////// ////////// ////////// ////////// ////////// ////////// //////////
    private void configureScaladoc(Project project, mainSourceSet, ScalaCompile mainScalaCompile) {
        project.tasks.withType(ScalaDoc).configureEach { ScalaDoc scalaDoc ->
            scalaDoc.conventionMapping.map("classpath", (new Callable<FileCollection>() {
                @Override
                FileCollection call() {
                    LOGGER.info "$NAME_PLUGIN ---> [configureScaladoc] >>>"
                    return project.files().from(mainScalaCompile.outputs, mainScalaCompile.classpath)
                }
            }))
            scalaDoc.setSource(mainSourceSet.extensions.getByType(ScalaSourceDirectorySet))
            scalaDoc.compilationOutputs.from(mainScalaCompile.outputs)
        }
        project.tasks.register(ScalaPlugin.SCALA_DOC_TASK_NAME, ScalaDoc) { ScalaDoc scalaDoc ->
            scalaDoc.setDescription("Generates Scaladoc for the main source code.")
            scalaDoc.setGroup(JavaBasePlugin.DOCUMENTATION_GROUP)
        }
    }

    private void printConfiguration(Project project, configName) {
        //project.configurations.all { Configuration config ->
        //    LOGGER.info "$NAME_PLUGIN ---> configurations.name: ${config.name} -------- ?????????"
        //    config.getDependencies().each { Dependency dep -> //
        //        LOGGER.info "    configurations.dependencies: ${dep.group}:${dep.name}:${dep.version}"
        //    }
        //    LOGGER.info ''
        //}
        project.configurations.named(configName).configure { Configuration config ->
            LOGGER.warn "$NAME_PLUGIN ---> configurations.name:${config.name} -------- ???"
            config.dependencies.each { Dependency dep -> //
                LOGGER.warn "    `${configName} ${dep.group}:${dep.name}:${dep.version}`"
            }
            LOGGER.warn ''
        }
    }

    private def genJavaCompileTaskName(srcSetNameMatchVariant) {
        return "compile${srcSetNameMatchVariant.capitalize()}JavaWithJavac"
    }

    private def genKotlinCompileTaskName(srcSetNameMatchVariant) {
        return "compile${srcSetNameMatchVariant.capitalize()}Kotlin"
    }

    private def genScalaCompileTaskName(srcSetNameMatchVariant) {
        return "compile${srcSetNameMatchVariant.capitalize()}Scala"
    }

    private def genMergeJavaResourceTaskName(srcSetNameMatchVariant) {
        return "merge${srcSetNameMatchVariant.capitalize()}JavaResource"
    }

    /*private List<Project> findMainSubProject(Project project) {
        // ?????????????????? subproject ?????????????????????????????????apply `com.android.application`??? project ?????????????????????
        LOGGER.info "$NAME_PLUGIN ---> [findMainSubProject]${project.rootProject} | name:${project.rootProject.name}, path:${project.rootProject.path}, version:${project.rootProject.version}"
        final subPrs = []
        project.rootProject.subprojects { proj ->
            println proj
            if (proj.plugins.findPlugin(ID_ANDROID_APP)) {
                println "findPlugin $ID_ANDROID_APP"
                subPrs.add(proj)
            }
        }
        println subPrs
        return subPrs
    }*/
}
