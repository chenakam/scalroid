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

import org.gradle.api.*

//import com.android.build.gradle.BaseExtension
//import com.android.build.gradle.BasePlugin

import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.attributes.Usage
import org.gradle.api.component.SoftwareComponentFactory
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFile
import org.gradle.api.file.SourceDirectorySet
import org.gradle.api.internal.file.DefaultFileCollectionFactory
import org.gradle.api.internal.file.DefaultFileLookup
import org.gradle.api.internal.file.collections.DefaultDirectoryFileTreeFactory
import org.gradle.api.internal.provider.PropertyHost
import org.gradle.api.internal.tasks.DefaultScalaSourceSet
import org.gradle.api.internal.tasks.DefaultSourceSetOutput
import org.gradle.api.internal.tasks.DefaultTaskDependencyFactory
import org.gradle.api.model.ObjectFactory
import org.gradle.api.plugins.Convention
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.plugins.JavaBasePlugin
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.plugins.jvm.internal.JvmPluginServices
import org.gradle.api.plugins.scala.ScalaBasePlugin
import org.gradle.api.plugins.scala.ScalaPlugin
import org.gradle.api.plugins.scala.ScalaPluginExtension
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.specs.Spec
import org.gradle.api.tasks.ScalaSourceDirectorySet
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.compile.AbstractCompile
import org.gradle.api.tasks.compile.CompileOptions
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.scala.IncrementalCompileOptions
import org.gradle.api.tasks.scala.ScalaCompile
import org.gradle.api.tasks.scala.ScalaDoc
import org.gradle.api.tasks.util.internal.PatternSets
import org.gradle.internal.nativeintegration.services.FileSystems
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.jvm.toolchain.JavaToolchainSpec
import org.gradle.language.base.plugins.LifecycleBasePlugin
import org.gradle.language.scala.tasks.AbstractScalaCompile

import javax.inject.Inject
import java.util.function.BiFunction

import static org.gradle.api.attributes.Usage.USAGE_ATTRIBUTE
import static org.gradle.api.internal.lambdas.SerializableLambdas.spec

interface ScalaAndroidCompatPluginExtension {
    Property<String> getMessage()

    Property<String> getGreeter()
}

class ScalaAndroidCompatPlugin implements Plugin<Project> {
    static String NAME_PLUGIN = 'scalroid'
    static String NAME_ANDROID_EXTENSION = 'android'
    static String NAME_SCALA_EXTENSION = 'scala'
    static String NAME_KOTLIN_EXTENSION = 'kotlin'
    static String ID_PLUGIN = "cash.bdo.$NAME_PLUGIN"
    static String ID_ANDROID_APP = 'com.android.application'
    static String ID_ANDROID_LIB = 'com.android.library'
    // 这个用法见`com.android.build.gradle.api.AndroidBasePlugin`文档。
    static String ID_ANDROID_BASE = 'com.android.base'
    static String ID_PRE_BUILD = 'preBuild'

    private final ObjectFactory factory
    private final SoftwareComponentFactory softCptFactory
    private final JvmPluginServices jvmServices

    //@VisibleForTesting
    //private final Map<String, SourceDirectorySet> sourceDirectorySetMap = new HashMap<>()

    @Inject
    ScalaAndroidCompatPlugin(ObjectFactory objectFactory, SoftwareComponentFactory softCptFactory, JvmPluginServices jvmServices) {
        this.factory = objectFactory
        this.softCptFactory = softCptFactory
        this.jvmServices = jvmServices
    }

    @Override
    void apply(Project project) {
        if (![ID_ANDROID_APP, ID_ANDROID_LIB, ID_ANDROID_BASE].any { project.plugins.findPlugin(it) }) {
            // apply plugins 具有顺序性。
            throw new ProjectConfigurationException("Please apply `$ID_ANDROID_APP` or `$ID_ANDROID_LIB` plugin before applying `$ID_PLUGIN` plugin.", new Throwable())
        }
        ScalaAndroidCompatPluginExtension extension = project.extensions.create(NAME_PLUGIN, ScalaAndroidCompatPluginExtension)

        // 1. 应用`ScalaBasePlugin`，与标准 Android 系列插件之间没有冲突。
        project.pluginManager.apply(ScalaBasePlugin) // or apply 'org.gradle.scala-base'
        ScalaPluginExtension scalaExtension = project.extensions.getByName(NAME_SCALA_EXTENSION)

        //final kotlinExtension = project.extensions.getByName(NAME_KOTLIN_EXTENSION)
        // org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension_Decorated
        //println "kotlinExtension: $kotlinExtension, class: ${kotlinExtension.class.name}"

        // project.path: :app, project.name: app, project.group: DemoMaterial3, project.version: unspecified
        println "project.path: ${project.path}, project.name: ${project.name}, project.group: ${project.group}, project.version: ${project.version}"
        println()

        addScalaPluginExtensionToScalroidClosure(extension, scalaExtension)

        // 2. 设置 Scala 源代码目录，并链接编译 Task 的依赖关系。
        linkScalaAndroidResourcesAndTasks(project, extension)
        // 3. 最后，加入本插件的`Task`。
        addPluginTask(project, extension, scalaExtension)
    }

    private void addPluginTask(Project project, ScalaAndroidCompatPluginExtension extension, ScalaPluginExtension scalaExtension) {
        project.task(NAME_PLUGIN) {
            // 设置会优先返回（可以写在外面）
            extension.message = 'Hi, scala lover!'
            extension.greeter = 'BDO.CASH Developer~'
            //dependsOn xxx
            doLast {
                println "${extension.message.get()} from ${extension.greeter.get()}" + "\nScala zinc version: ${extension.scala.zincVersion.get()}/${scalaExtension.zincVersion.get()}"
            }
        }
    }

    private void linkScalaAndroidResourcesAndTasks(Project project, ScalaAndroidCompatPluginExtension scalroid) {
        Object androidExtension = project.extensions.getByName(NAME_ANDROID_EXTENSION)
        boolean isLibrary
        Object androidPlugin
        if (project.plugins.hasPlugin(ID_ANDROID_LIB)) {
            isLibrary = true
            androidPlugin = project.plugins.findPlugin(ID_ANDROID_LIB)
        } else {
            isLibrary = false
            androidPlugin = project.plugins.findPlugin(ID_ANDROID_APP)
        }
        addPluginExtensionToAndroidClosure(androidExtension, scalroid)

        //final scalaBasePlugin = project.plugins.findPlugin(ScalaBasePlugin)
        final incrementalAnalysisUsage = factory.named(Usage, "incremental-analysis")
        final workDir = project.layout.buildDirectory.file(NAME_PLUGIN).get().asFile

        project.tasks.getByName(ID_PRE_BUILD).doLast { workDir.mkdirs() }

        androidExtension.sourceSets.each { sourceSet -> // androidTest, test, main
            // 实测在`project.afterEvaluate`前后，`sourceSet`数量不一样。
            // 但是如果不执行这次，`build.gradle`中的`sourceSets.main.scala.xxx`会报错。
            // 在这里执行之后，会 apply `build.gradle`中的`sourceSets.main.scala.xxx`的设置。
            // TODO: 是否会把`sourceSets.main`中的设置 apply 到其它 variants，暂未测试，猜测应该会。
            resolveScalaSrcDirsToAndroidSourceSetsClosure(project, sourceSet, false)
        }

        final mainSourceSet = androidExtension.sourceSets.getByName('main')
        println()
        println "mainSourceSet: $mainSourceSet"
        println()
        println "|||||||||| |||||||||| |||||||||| |||||||||| |||||||||| AFTER EVALUATE |||||||||| |||||||||| |||||||||| |||||||||| ||||||||||"

        project.afterEvaluate {
            println()
            ////////// ////////// ////////// ////////// ////////// ////////// ////////// ////////// ////////// ////////// ////////// ////////// //////////
            //project.configurations.all { Configuration config ->
            //    println "$NAME_PLUGIN ---> configurations.name: ${config.name} -------- √√√"
            //    config.getDependencies().each { Dependency dep -> //
            //        println "    configurations.dependencies: ${dep.group}:${dep.name}:${dep.version}"
            //    }
            //    println()
            //}
            project.configurations.named('implementation').configure { Configuration config ->
                println "$NAME_PLUGIN ---> configurations.name: ${config.name} -------- √"
                config.getDependencies().each { Dependency dep -> //
                    println "    implementation.dependencies: ${dep.group}:${dep.name}:${dep.version}"
                }
                println()
            }
            //dependencies {
            //    这里的`implementation`就是`Configuration`的名字，是在`Android`插件中定义的。
            //    所以需要什么就在`dependencies`中找什么，然后按照下面的代码示例查找。
            //    implementation "androidx.core:core-ktx:$ktx.Version"
            //    implementation "org.scala-lang:scala-library:$scala2.Version"
            //}
            // 为了让`ScalaCompile`能够找到`Scala`的版本，需要在`ScalaCompile`的`classpath`中添加包含"library"的 jar。详见：`scalaRuntime.inferScalaClasspath(compile.getClasspath())`。
            // `classpath`需要`FileCollection`，而`Configuration`继承自`FileCollection`，所以可以直接使用`Configuration`。
            // 具体写在下边`.register("xxx", ScalaCompile)`：
            // scalaCompile.classpath = project.configurations.create("scalaClasspathFor${sourceSet.name.capitalize()}").extendsFrom(project.configurations.implementation)

            ////////// ////////// ////////// ////////// ////////// ////////// ////////// ////////// ////////// ////////// ////////// ////////// //////////
            final variantsAll = androidExtension.testVariants + (isLibrary ? androidExtension.libraryVariants : androidExtension.applicationVariants)
            final variantsNames = new java.util.HashSet<String>()
            variantsAll.each { variant -> variantsNames.add(variant.name) }

            println()
            println "$NAME_PLUGIN ---> variantsNames: ${variantsNames.join(", ")}"
            println()

            androidExtension.sourceSets.each { sourceSet -> // androidTest, test
                println "$NAME_PLUGIN <<<===>>> sourceSet: $sourceSet"

                resolveScalaSrcDirsToAndroidSourceSetsClosure(project, sourceSet, true)

                // 把所有 scala 目录添加回 java 目录。也就是说，所有的目录都能写 java 代码。
                // 但我觉得还是不要了，如果有需要，让其自己手动显式设置。因为如果这样的话，kotlin 也得设置。
                def ktDirs = []
                if (sourceSet.kotlin) ktDirs = sourceSet.kotlin.srcDirs

                println "$NAME_PLUGIN --- 1 ---> java.srcDirs:" + sourceSet.java.srcDirs
                println "$NAME_PLUGIN --- 1 ---> scala.srcDirs:" + sourceSet.scala.srcDirs
                println "$NAME_PLUGIN --- 1 ---> kotlin.srcDirs:" + ktDirs
                println()

                ////////// ////////// ////////// ////////// ////////// ////////// ////////// ////////// ////////// ////////// ////////// ////////// //////////
                // 实测发现：`sourceSet.name`和`variant.name`有些不同：
                // 例如：`sourceSet.name`是`androidTestGithubDebug`，而`variant.name`是`githubDebugAndroidTest`。但已有的`compileXxxJava/Kotlin` task 名字是以`variant.name`为准的。
                // 所以需要统一。

                def srcSetNameMatchVariant = sourceSet.name
                final androidTest = 'androidTest'; final test = 'test'
                println "$NAME_PLUGIN ---> contains:${variantsNames.contains(srcSetNameMatchVariant)}"
                if (!variantsNames.contains(srcSetNameMatchVariant)) {
                    if (srcSetNameMatchVariant.startsWith(androidTest)) {
                        srcSetNameMatchVariant = srcSetNameMatchVariant.substring(androidTest.length()) + androidTest.capitalize()
                    } else if (srcSetNameMatchVariant.startsWith(test)) {
                        srcSetNameMatchVariant = srcSetNameMatchVariant.substring(test.length()) + test.capitalize()
                    } else if (srcSetNameMatchVariant.contains(androidTest.capitalize()) || srcSetNameMatchVariant.contains(test.capitalize()) || srcSetNameMatchVariant.contains(androidTest) || srcSetNameMatchVariant.contains(test)) {
                        // 不在开头，那就在中间或结尾，即：`androidTest`或`test`的首字母大写。
                        println "$NAME_PLUGIN ---> exception:${srcSetNameMatchVariant}"
                        throw new ProjectConfigurationException("sourceSet.name(${sourceSet.name}) not contains in `variants.names` and not within the expected range, please check.", new Throwable())
                    }
                }
                //ScalaRuntime scalaRuntime = project.extensions.getByName(SCALA_RUNTIME_EXTENSION_NAME)
                configureScalaCompile(project, sourceSet, srcSetNameMatchVariant, incrementalAnalysisUsage)
            }

            println "||||||||| |||||||||| |||||||||| link all variants depends on |||||||||| |||||||||| ||||||||||"
            variantsAll.each { variant ->
                //variant = "$flavor$buildType" // e.g. "googleplayRelease", "githubDebug".
                //"compile${variant.name.capitalize()}Scala" // `.capitalize()`首字母大写
                println()

                linkScalaCompileDependsOn(project, androidPlugin, androidExtension, workDir, variant)

                println("<<<<<<<<<<<============ ${variant.name} DONE ===============>>>>>>>>>>>>>>>>>>>")
            }

            ////////// ////////// ////////// ////////// ////////// ////////// ////////// ////////// ////////// ////////// ////////// ////////// //////////
            // 这一步需要等上面`linkScalaCompileDependsOn()`触发`ScalaCompile`配置完成，才能继续。
            configureScaladocAndIncrementalAnalysis(project, mainSourceSet)
        }
    }

    void configureScaladocAndIncrementalAnalysis(Project project, Object mainSourceSet) {
        configureScaladoc(project, mainSourceSet)

        final Configuration incrementalAnalysisElements = project.configurations.getByName("incrementalScalaAnalysisElements")
        final scalaCompileTaskNameMain = genScalaCompileTaskName(mainSourceSet.name) // "compileMainScala" //mainSourceSet.getCompileTaskName("scala")
        final TaskProvider<AbstractScalaCompile> compileScala = project.getTasks().withType(AbstractScalaCompile).named(scalaCompileTaskNameMain)
        final Provider<RegularFile> compileScalaMapping = project.layout.buildDirectory.file("tmp/scala/compilerAnalysis/${scalaCompileTaskNameMain}.mapping")
        compileScala.configure(task -> task.getAnalysisMappingFile().set(compileScalaMapping))
        incrementalAnalysisElements.outgoing.artifact(compileScalaMapping, configurablePublishArtifact -> configurablePublishArtifact.builtBy(compileScala))
    }
    /*
     * 把 scalroid 加入到 android 下面。可以这样写：
     * <pre>
     * android {
     *   scalroid {
     *     <i>message = 'Hi'</i>
     *     <i>greeter = 'Gradle'</i>
     *   }
     * }
     * </pre>
     */

    private void addPluginExtensionToAndroidClosure(Object android, Object scalroid) {
        android.metaClass."$NAME_PLUGIN" = scalroid
        // 等同于：`android.metaClass."get${NAME_PLUGIN.capitalize()}" = scalroid`，详见`InvokerHelper.invokeMethod()`。
    }

    //scalroid {
    //    scala.zincVersion = '1.3.5'
    //    ...
    //}
    private void addScalaPluginExtensionToScalroidClosure(Object scalroid, Object scala) {
        scalroid.metaClass."$NAME_SCALA_EXTENSION" = scala
    }

    private void resolveScalaSrcDirsToAndroidSourceSetsClosure(Project project, Object sourceSet, boolean evaluated) {
        println "$NAME_PLUGIN ---> sourceSet.name:${sourceSet.name}, displayName:${sourceSet.displayName}"
        println()
        try {
            println "$NAME_PLUGIN ---> sourceSet.extensions:${sourceSet.extensions}" //org.gradle.internal.extensibility.DefaultConvention

            if (sourceSet.extensions.findByName("scala")) return

            final displayName = sourceSet.displayName //(String) InvokerHelper.invokeMethod(sourceSet, "getDisplayName", null)
            Convention sourceSetConvention = sourceSet.convention //(Convention) InvokerHelper.getProperty(sourceSet, "convention")
            DefaultScalaSourceSet scalaSourceSet = new DefaultScalaSourceSet(displayName, factory)
            sourceSetConvention.plugins.put("scala", scalaSourceSet)
            sourceSet.extensions.add(ScalaSourceDirectorySet, "scala", scalaSourceSet.scala)

            final SourceDirectorySet scalaDirectorySet = scalaSourceSet.scala
            scalaDirectorySet.srcDir(project.file("src/${sourceSet.name}/scala"))
            //sourceSet.allJava.source(scalaDirectorySet)
            //sourceSet.allSource.source(scalaDirectorySet)

            // Explicitly capture only a FileCollection in the lambda below for compatibility with configuration-cache.
            FileCollection scalaSource = scalaDirectorySet
            sourceSet.resources.filter.exclude(spec(element -> scalaSource.contains(element.file)))
        } catch (e) {
            println "$NAME_PLUGIN ||||||| sourceSet.name:${sourceSet.name}, exception:${e}"
            println()
        }
    }

    private void configureScalaCompile(Project project, Object sourceSet, String srcSetNameMatchVariant, Usage incrementalAnalysisUsage) {
        Configuration classpath = project.configurations.getByName(sourceSet.implementationConfigurationName)
        Configuration incrementalAnalysis = project.configurations.create("incrementalScalaAnalysisFor${srcSetNameMatchVariant.capitalize()}")
        incrementalAnalysis.setVisible(false)
        incrementalAnalysis.setDescription("Incremental compilation analysis files for ${sourceSet.displayName}")
        incrementalAnalysis.setCanBeResolved(true)
        incrementalAnalysis.setCanBeConsumed(false)
        incrementalAnalysis.extendsFrom(classpath)
        incrementalAnalysis.attributes.attribute(USAGE_ATTRIBUTE, incrementalAnalysisUsage)

        final ScalaSourceDirectorySet scalaDirectorySet = sourceSet.extensions.getByType(ScalaSourceDirectorySet)
        println "configureScalaCompile | --->>> 1 --->>> scalaDirectorySet.name:${scalaDirectorySet.name}, scalaDirectorySet.displayName:${scalaDirectorySet.displayName}"
        println()

//        final namingScheme = new ClassDirectoryBinaryNamingScheme(srcSetNameMatchVariant)
//        `sourceSet.getClassesTaskName()`的实现（只用作参考）：
//         String getClassesTaskName() { return getTaskName(null, "classes") }
//        String getCompileTaskName(String language) { return getTaskName("compile", language) }
//         String getTaskName(@Nullable String verb, @Nullable String target) { return namingScheme.getTaskName(verb, target) }

        final compileTaskName = genScalaCompileTaskName(srcSetNameMatchVariant)
        final TaskProvider<ScalaCompile> scalaCompileTask = project.tasks.register(compileTaskName, ScalaCompile) { ScalaCompile scalaCompile ->
            println "configureScalaCompile | --->>> 2 configing --->>> ${compileTaskName}"

            scalaCompile.classpath = project.configurations.create("scalaBaseClasspathFor${srcSetNameMatchVariant.capitalize()}").extendsFrom(project.configurations.implementation)
            // + factory.fileCollection().from(androidExtension.bootClasspathConfig.fullBootClasspath)
            // 等同于 project.files(androidExtension.bootClasspathConfig.fullBootClasspath)

            scalaCompile.description = "Compiles the ${scalaDirectorySet}."
            scalaCompile.source = scalaDirectorySet
            scalaCompile.javaLauncher.convention(getToolchainTool(project, JavaToolchainService::launcherFor))
            scalaCompile.analysisMappingFile.set(project.layout.buildDirectory.file("tmp/scala/compilerAnalysis/${scalaCompile.name}.mapping"))

            // cannot compute at task execution time because we need association with source set
            IncrementalCompileOptions incrementalOptions = scalaCompile.scalaCompileOptions.incrementalOptions
            incrementalOptions.analysisFile.set(project.layout.buildDirectory.file("tmp/scala/compilerAnalysis/${scalaCompile.name}.analysis"))
            incrementalOptions.classfileBackupDir.set(project.layout.buildDirectory.file("tmp/scala/classfileBackup/${scalaCompile.name}.bak"))

            scalaCompile.analysisFiles.from(incrementalAnalysis.incoming.artifactView({ viewConfig ->
                viewConfig.lenient(true)
                viewConfig.componentFilter(new IsProjectComponent())
            }).files)
            scalaCompile.dependsOn(scalaCompile.analysisFiles)
        }

//        if (sourceSet.name != 'main') return

//        // TODO: 把该方法等效复制过来
//        JvmPluginsHelper.configureOutputDirectoryForSourceSet(sourceSet, scalaDirectorySet, project, scalaCompileTask, scalaCompileTask.map(new Transformer<CompileOptions, ScalaCompile>() {
//            @Override
//            public CompileOptions transform(ScalaCompile scalaCompile) {
//                return scalaCompile.getOptions()
//            }
//        }))

        final options = scalaCompileTask.map(new Transformer<CompileOptions, ScalaCompile>() {
            @Override
            public CompileOptions transform(ScalaCompile scalaCompile) {
                return scalaCompile.getOptions()
            }
        })
        // 目录与 kotlin 保持一致
        scalaDirectorySet.destinationDirectory.convention(project.layout.buildDirectory.dir("tmp/scala-classes/${scalaDirectorySet.name}/${srcSetNameMatchVariant}"))

        // TODO: 没有输出目录
        sourceSet.metaClass.properties.each { groovy.lang.MetaBeanProperty it ->
            try {
                println "configureScalaCompile | --->>> 3 --->>> key:${it.getName()}, value:${it.getProperty(sourceSet)}"
            } catch (e) {
                println "configureScalaCompile | --->>> 3 --->>> key:${it.getName()}, exception:${e}"
            }
        }
//        com.android.build.gradle.internal.api.DefaultAndroidSourceSet
//        com.android.build.gradle.api.AndroidSourceDirectorySet
//        com.android.build.api.dsl.AndroidSourceDirectorySet
//        com.android.build.gradle.AppPlugin

        final sourceSetDisplayName = sourceSet.displayName
        final fileLookup = new DefaultFileLookup()
        final fileCollectionFactory = new DefaultFileCollectionFactory(fileLookup.pathToFileResolver, DefaultTaskDependencyFactory.withNoAssociatedProject(), new DefaultDirectoryFileTreeFactory(), PatternSets.getNonCachingPatternSetFactory(), PropertyHost.NO_OP, FileSystems.getDefault());
        // TODO: 最后一块骨头了
        // DefaultSourceSetOutput sourceSetOutput = Cast.cast(DefaultSourceSetOutput.class, sourceSet.getOutput())
        DefaultSourceSetOutput sourceSetOutput = new DefaultSourceSetOutput(sourceSetDisplayName, fileLookup.fileResolver, fileCollectionFactory)
        sourceSetOutput.addClassesDir(scalaDirectorySet.destinationDirectory)
        sourceSetOutput.registerClassesContributor(scalaCompileTask)
        sourceSetOutput.generatedSourcesDirs.from(options.flatMap(CompileOptions::getGeneratedSourceOutputDirectory))
        scalaDirectorySet.compiledBy(scalaCompileTask, AbstractCompile::getDestinationDirectory)

        final classesTaskName = genScalaClassesTaskName(srcSetNameMatchVariant) // sourceSet.getClassesTaskName()
        project.tasks.register(classesTaskName, classesTask -> {
            classesTask.setGroup(LifecycleBasePlugin.BUILD_GROUP)
            classesTask.setDescription("Assembles ${sourceSetOutput}.")
            classesTask.dependsOn(sourceSetOutput.dirs)
//            classesTask.dependsOn(sourceSet.getCompileJavaTaskName())
//            classesTask.dependsOn(sourceSet.getProcessResourcesTaskName())
        })
        project.tasks.named(classesTaskName) { Task task -> task.dependsOn scalaCompileTask }
//        project.tasks.each { // 以下输出猜的没错
//            if (it.name.contains('class') || it.name.contains('Class')) {
//                // `bundle{Variant}[XxxTest]ClassesToCompileJar`
//                println "configureScalaCompile | --->>> 4 --->>> task.name:${it.name}, task.type:${it.class.name}"
//            }
//        }
    }

    private void linkScalaCompileDependsOn(Project project, Object androidPlugin, Object androidExtension, File workDir, Object variant) {
        println "$NAME_PLUGIN ---> androidPlugin: ${androidPlugin}" // com.android.build.gradle.AppPlugin@8ab260a
        println "$NAME_PLUGIN ---> workDir: ${workDir.path}" // /Users/{PATH-TO-}/demo-material-3/app/build/scalroid
        println "$NAME_PLUGIN ---> variant: ${variant.name}" // githubDebug

        //final javaCompileProvider = project.tasks.withType(JavaCompile).named("compile${variant.name.capitalize()}JavaWithJavac")
        //if (javaCompileProvider.orNull != null) { final javaCompile = javaCompileProvider.get() }
        final JavaCompile javaCompile = project.tasks.findByName("compile${variant.name.capitalize()}JavaWithJavac")
        if (javaCompile != null) {
            // 获取前面已经注册的`scalaCompileTask`。见`project.tasks.register()`文档。
            project.tasks.withType(ScalaCompile).getByName("compile${variant.name.capitalize()}Scala") { ScalaCompile scalaCompile ->
                println "$NAME_PLUGIN ---> scalaCompile: ${scalaCompile}"

                //println "$NAME_PLUGIN ---> javaCompile.classpath: ${javaCompile.classpath.asList().join(",\n")}"
                // 不能`scalaCompile.classpath = javaCompile.classpath`，这里似乎晚了（先走的是`project.tasks.register()`的那个 Closure，然后`project.tasks.withType(ScalaCompile).configureEach(compile -> {})`，见`ScalaBasePlugin`），更多解释见上面。

                println "$NAME_PLUGIN ---> javaCompile.destinationDirectory: ${javaCompile.getDestinationDirectory().orNull}"

//                scalaCompile.destinationDirectory.set(javaCompile.destinationDirectory)
//                scalaCompile.sourceCompatibility = javaCompile.sourceCompatibility
//                scalaCompile.targetCompatibility = javaCompile.targetCompatibility
//                scalaCompile.scalaCompileOptions.encoding = javaCompile.options.encoding
//                scalaCompile.options.encoding = javaCompile.options.encoding

//                javaCompile.dependsOn scalaCompile

                final scalaClasses = project.tasks.findByName(genScalaClassesTaskName(variant.name))
                if (scalaClasses != null) {
                    javaCompile.dependsOn scalaClasses
                }

                final kotlinCompile = project.tasks.findByName("compile${variant.name.capitalize()}Kotlin")
                if (kotlinCompile != null) {
                    println "$NAME_PLUGIN ---> kotlinCompile: ${kotlinCompile}, class: ${kotlinCompile.class.name}" // org.jetbrains.kotlin.gradle.tasks.KotlinCompile
//                    kotlinCompile.dependsOn scalaCompile
                    if (scalaClasses != null) {
                        kotlinCompile.dependsOn scalaClasses
                    }
                } else {
                    println "$NAME_PLUGIN ---> kotlinCompile: null"
                }
            }
        } else {
            println "$NAME_PLUGIN ---> javaCompile: null"
        }

        return

//        ScalaRuntime scalaRuntime = project.extensions.getByName(SCALA_RUNTIME_EXTENSION_NAME)
//        //String scalaVersion = scalaRuntime.getScalaVersion()
//
//        final javaCompileTp = {}
//
//
//        // To prevent locking classes.jar by JDK6's URLClassLoader
//        def libraryClasspath = javaCompileTp.classpath.grep { it.name != "classes.jar" }
//        def scalaVersion = scalaVersionFromClasspath(libraryClasspath)
//        if (!scalaVersion) {
//            return
//        }
//        project.logger.info("scala-library version=${scalaVersion} detected")
//        def zincConfigurationName = "androidScalaPluginZincFor" + javaCompileTp.name
//        def zincConfiguration = project.configurations.findByName(zincConfigurationName)
//        if (!zincConfiguration) {
//            zincConfiguration = project.configurations.create(zincConfigurationName)
//            project.dependencies.add(zincConfigurationName, "com.typesafe.zinc:zinc:0.3.7")
//        }
//        def compilerConfigurationName = "androidScalaPluginScalaCompilerFor" + javaCompileTp.name
//        def compilerConfiguration = project.configurations.findByName(compilerConfigurationName)
//        if (!compilerConfiguration) {
//            compilerConfiguration = project.configurations.create(compilerConfigurationName)
//            project.dependencies.add(compilerConfigurationName, "org.scala-lang:scala-compiler:$scalaVersion")
//        }
//        def variantWorkDir = getVariantWorkDir(workDir, variant)
//        def scalaCompileTask = project.tasks.create("compile${variant.name.capitalize()}Scala", ScalaCompile)
//        def scalaSources = variant.variantData.variantConfiguration.sortedSourceProviders.inject([]) { acc, val -> acc + val.java.sourceFiles }
//        scalaCompileTask.source = scalaSources
//        scalaCompileTask.destinationDir = javaCompileTp.destinationDir
//        scalaCompileTask.sourceCompatibility = javaCompileTp.sourceCompatibility
//        scalaCompileTask.targetCompatibility = javaCompileTp.targetCompatibility
//        scalaCompileTask.scalaCompileOptions.encoding = javaCompileTp.options.encoding
//        scalaCompileTask.classpath = javaCompileTp.classpath + project.files(androidPlugin.androidBuilder.getBootClasspath(false))
//        scalaCompileTask.scalaClasspath = compilerConfiguration.asFileTree
//        scalaCompileTask.zincClasspath = zincConfiguration.asFileTree
//        scalaCompileTask.scalaCompileOptions.incrementalOptions.analysisFile = new File(variantWorkDir, "analysis.txt")
//
//        if (extension.addparams) {
//            scalaCompileTask.scalaCompileOptions.additionalParameters = [extension.addparams]
//        }
//
//        def dummyDestinationDir = new File(variantWorkDir, "javaCompileDummyDestination")
//        // TODO: More elegant way
//        def dummySourceDir = new File(variantWorkDir, "javaCompileDummySource")
//        // TODO: More elegant way
//        def javaCompileOriginalDestinationDir = new AtomicReference<File>()
//        def javaCompileOriginalSource = new AtomicReference<FileCollection>()
//        def javaCompileOriginalOptionsCompilerArgs = new AtomicReference<List<String>>()
//        javaCompileTp.doFirst {
//            // Disable compilation
//            javaCompileOriginalDestinationDir.set(javaCompileTp.destinationDir)
//            javaCompileOriginalSource.set(javaCompileTp.source)
//            javaCompileTp.destinationDir = dummyDestinationDir
//            if (!dummyDestinationDir.exists()) {
//                FileUtils.forceMkdir(dummyDestinationDir)
//            }
//            def dummySourceFile = new File(dummySourceDir, "Dummy.java")
//            if (!dummySourceFile.exists()) {
//                FileUtils.forceMkdir(dummySourceDir)
//                dummySourceFile.withWriter { it.write("class Dummy{}") }
//            }
//            javaCompileTp.source = [dummySourceFile]
//            def compilerArgs = javaCompileTp.options.compilerArgs
//            javaCompileOriginalOptionsCompilerArgs.set(compilerArgs)
//            javaCompileTp.options.compilerArgs = compilerArgs + "-proc:none"
//        }
//        javaCompileTp.outputs.upToDateWhen {
//            false
//        }
//        javaCompileTp.doLast {
//            FileUtils.deleteDirectory(dummyDestinationDir)
//            javaCompileTp.destinationDir = javaCompileOriginalDestinationDir.get()
//            javaCompileTp.source = javaCompileOriginalSource.get()
//            javaCompileTp.options.compilerArgs = javaCompileOriginalOptionsCompilerArgs.get()
//
//            // R.java is appended lazily
//            scalaCompileTask.source = [] + new TreeSet(scalaCompileTask.source.collect { it } + javaCompileTp.source.collect { it })
//            // unique
//            def noisyProperties = ["compiler", "includeJavaRuntime", "incremental", "optimize", "useAnt"]
//            InvokerHelper.setProperties(scalaCompileTask.options,
//                    javaCompileTp.options.properties.findAll { !noisyProperties.contains(it.key) })
//            noisyProperties.each { property ->
//                // Suppress message from deprecated/experimental property as possible
//                if (!javaCompileTp.options.hasProperty(property) || !scalaCompileTask.options.hasProperty(property)) {
//                    return
//                }
//                if (scalaCompileTask.options[property] != javaCompileTp.options[property]) {
//                    scalaCompileTask.options[property] = javaCompileTp.options[property]
//                }
//            }
//            scalaCompileTask.execute()
//            project.logger.lifecycle(scalaCompileTask.path)
//        }
    }

    String genScalaCompileTaskName(String srcSetNameMatchVariant) {
        return "compile${srcSetNameMatchVariant.capitalize()}Scala"
    }

    String genScalaClassesTaskName(String srcSetNameMatchVariant) {
        return "classes${srcSetNameMatchVariant.capitalize()}Scala"
    }

    private static void configureScaladoc(Project project, Object mainSourceSet) {
        println()
        println "configureScaladoc | ---> sourceSet.main: ${mainSourceSet.getClass().getName()}" // com.android.build.gradle.internal.api.DefaultAndroidSourceSet

        project.tasks.withType(ScalaDoc).configureEach { ScalaDoc scalaDoc ->
            scalaDoc.conventionMapping.map("classpath", () -> {
                ConfigurableFileCollection files = project.files()
//                files.from(mainSourceSet.getOutput())
                files.from(mainSourceSet.getCompileClasspath())
                return files
            })
            scalaDoc.setSource(mainSourceSet.extensions.getByType(ScalaSourceDirectorySet))
//            scalaDoc.compilationOutputs.from(mainSourceSet.getOutput())
        }
        project.tasks.register(ScalaPlugin.SCALA_DOC_TASK_NAME, ScalaDoc, scalaDoc -> {
            scalaDoc.setDescription("Generates Scaladoc for the main source code.")
            scalaDoc.setGroup(JavaBasePlugin.DOCUMENTATION_GROUP)
        })
    }

    private <T> Provider<T> getToolchainTool(Project project, BiFunction<JavaToolchainService, JavaToolchainSpec, Provider<T>> toolMapper) {
        final JavaPluginExtension extension = extensionOf(project, JavaPluginExtension.class)
        final JavaToolchainService service = extensionOf(project, JavaToolchainService.class)
        return toolMapper.apply(service, extension.getToolchain())
    }

    private <T> T extensionOf(ExtensionAware extensionAware, Class<T> type) {
        return extensionAware.getExtensions().getByType(type)
    }

    private static class IsProjectComponent implements Spec<ComponentIdentifier> {
        @Override
        public boolean isSatisfiedBy(ComponentIdentifier element) {
            return element instanceof ProjectComponentIdentifier
        }
    }
}
