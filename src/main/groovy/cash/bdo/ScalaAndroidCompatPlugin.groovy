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
import org.gradle.api.Task
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
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
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.specs.Spec
import org.gradle.api.tasks.ScalaSourceDirectorySet
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.compile.AbstractCompile
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.scala.IncrementalCompileOptions
import org.gradle.api.tasks.scala.ScalaCompile
import org.gradle.api.tasks.scala.ScalaDoc
import org.gradle.api.tasks.util.PatternSet
import org.gradle.internal.Factory

import javax.annotation.Nullable
import javax.inject.Inject
import java.util.concurrent.Callable

import static org.gradle.api.attributes.Usage.USAGE_ATTRIBUTE
import static org.gradle.api.internal.lambdas.SerializableLambdas.spec

interface ScalroidExtension {
    static final DEF_MESSAGE = 'Hi, SCALA lover!'
    static final DEF_GREETER = "${ScalaAndroidCompatPlugin.ID_PLUGIN.toUpperCase()} Developer~"

    /** 默认值 false，表示：kotlin 代码引用 scala，但 scala 不引用 kotlin。这样的话`Task`不涉及循环依赖，编译正常进行。
     * 但更为复杂的情况是：
     * 代码交叉引用：即 kotlin 引用 scala，scala 又引用 kt。这里提供一个苟且的技巧：
     * 1. Clean 项目（运行`./gradlew clean`）；
     * 2. 先把 scala 中引用 kt 的代码都注释掉，并将本变量置为默认值 false（不用点击 Sync Now，点击也无妨）；
     * 3. 编译直到通过（`./gradlew :app:compile{VARIANT}JavaWithJavac`），目录 app/build/tmp/ 下的 kotlin-classes 和 scala-classes 都有 VARIANT 相关的输出；
     * 4. 将本变量置为默认值 true，并将在步骤 2 中注释掉的 scala 中引用 kt 的代码都取消注释；
     * 5. 编译直到成功。
     *
     * 注意：每次 Clean 项目，都要将上述步骤重来一遍。*/
    Property<Boolean> getScalaCodeReferToKt()
    /** 默认值 true。其它同上。看 scala/kotlin 哪个注释的代价小。*/
    Property<Boolean> getKtCodeReferToScala()

    ListProperty<String> getJavaDirsExcludes()

    Property<String> getMessage()

    Property<String> getGreeter()
}

class ScalaAndroidCompatPlugin implements Plugin<Project> {
    // TODO: e.g.
    //  `./gradlew :app:compileGithubDebugJavaWithJavac --stacktrace`
    //  `./gradlew :app:compileGithubDebugJavaWithJavac --info` for LOG.info('...')
    //  `./gradlew :app:compileGithubDebugJavaWithJavac --debug`
    // LOG 避免与某些 Closure 的父类 LOGGER 相冲突
    protected static final Logger LOG = Logging.getLogger(ScalaAndroidCompatPlugin)

    static final NAME_PLUGIN = 'scalroid'
    static final NAME_ANDROID_EXTENSION = 'android'
    static final NAME_SCALA_EXTENSION = 'scala'
    static final NAME_KOTLIN_EXTENSION = 'kotlin'
    static final ID_PLUGIN = "cash.bdo.$NAME_PLUGIN"
    static final ID_ANDROID_APP = 'com.android.application'
    static final ID_ANDROID_LIB = 'com.android.library'
    // 这个用法见`com.android.build.gradle.api.AndroidBasePlugin`文档。
    static final ID_ANDROID_BASE = 'com.android.base'
    static final ID_KOTLIN_ANDROID = 'org.jetbrains.kotlin.android'
    static final ID_KOTLIN_ANDROID_ABBR = 'kotlin-android' // 上面的缩写
    static final ID_PRE_BUILD = 'preBuild'
    static final ID_TEST_TO_FILE = 'testToFile'

    static final main = 'main'
    static final test = 'test'
    static final androidTest = 'androidTest'
    static final debug = 'debug'
    static final release = 'release'

    private boolean isCheckArgsWarningShowed = false

    private void checkArgsProbablyWarning(ScalroidExtension ext) {
        if (isCheckArgsWarningShowed) return
        final scalaReferKt = ext.scalaCodeReferToKt.get()
        final ktReferScala = ext.ktCodeReferToScala.get()
        final checked = false
        final lineMsg = "maybe error"
        if (scalaReferKt == ktReferScala && scalaReferKt == checked) {
            LOG.warn ""
            LOG.warn "* WARNING: parameter values may be set incorrectly (both `$checked`). Are you sure them?"
            LOG.warn "  android {"
            LOG.warn "    ${NAME_PLUGIN} {"
            LOG.warn "      scalaCodeReferToKt = ${scalaReferKt} // $lineMsg"
            LOG.warn "      ktCodeReferToScala = ${ktReferScala} // $lineMsg"
            LOG.warn "    }"
            LOG.warn "    ..."
            isCheckArgsWarningShowed = true
        }
    }

    private void testPrintParameters(Project project) {
        LOG.warn "applying plugin `$ID_PLUGIN` by ${project}"
        // project.path: :app, project.name: app, project.group: DemoMaterial3, project.version: unspecified
        //LOG.info "$NAME_PLUGIN ---> project.path: ${project.path}, project.name: ${project.name}, project.group: ${project.group}, project.version: ${project.version}"
        //LOG.info ''
    }
    private final ObjectFactory factory
    private final SoftwareComponentFactory softCptFactory
    private final JvmPluginServices jvmServices
    private final Factory<PatternSet> patternSetFactory

    @Inject
    ScalaAndroidCompatPlugin(ObjectFactory objectFactory, SoftwareComponentFactory softCptFactory, JvmPluginServices jvmServices, Factory<PatternSet> patternSetFactory) {
        this.factory = objectFactory
        this.softCptFactory = softCptFactory
        this.jvmServices = jvmServices
        this.patternSetFactory = patternSetFactory
    }

    @Override
    void apply(Project project) {
        testPrintParameters(project)
        if (![ID_ANDROID_APP, ID_ANDROID_LIB, ID_ANDROID_BASE].any { project.plugins.findPlugin(it) }) {
            // apply plugins 具有顺序性。
            throw new ProjectConfigurationException("Please apply `$ID_ANDROID_APP` or `$ID_ANDROID_LIB` plugin before applying `$ID_PLUGIN` plugin.", new Throwable())
        }
        if (!project.plugins.findPlugin(ID_KOTLIN_ANDROID)) {
            throw new ProjectConfigurationException("Please apply `$ID_KOTLIN_ANDROID` (or `$ID_KOTLIN_ANDROID_ABBR` for addr) plugin before applying `$ID_PLUGIN` plugin.", new Throwable())
        }
        ScalroidExtension extension = project.extensions.create(NAME_PLUGIN, ScalroidExtension)

        // 1. 应用`ScalaBasePlugin`，与标准 Android 系列插件之间没有冲突。
        project.pluginManager.apply(ScalaBasePlugin) // or apply 'org.gradle.scala-base'
        ScalaPluginExtension scalaExtension = project.extensions.getByName(NAME_SCALA_EXTENSION)
        addScalaPluginExtensionToScalroidClosure(extension, scalaExtension)

        final isLibrary = project.plugins.hasPlugin(ID_ANDROID_LIB)
        // 2. 设置 Scala 源代码目录，并链接编译 Task 的依赖关系。
        linkScalaAndroidResourcesAndTasks(project, extension, isLibrary)
        // 3. 最后，加入本插件的`Task`。
        addThisPluginTask(project, extension, scalaExtension, isLibrary)
    }

    private void addThisPluginTask(Project project, ScalroidExtension extension, ScalaPluginExtension scalaExtension, boolean isLibrary) {
        project.task(NAME_PLUGIN) {
            // 设置会优先返回（写在`build.gradle`里的）
            extension.scalaCodeReferToKt = false
            extension.ktCodeReferToScala = true
            extension.javaDirsExcludes = factory.listProperty(String)
            extension.message = ScalroidExtension.DEF_MESSAGE
            extension.greeter = ScalroidExtension.DEF_GREETER
            doLast {
                final version = scalaExtension.zincVersion.get()
                // `extension.convention.plugins.get(NAME_SCALA_EXTENSION).zincVersion.get()`
                assert version == extension.scala.zincVersion.get()
                LOG.error "${extension.message.get()} by ${extension.greeter.get()}"
                LOG.error "Scala zinc version: $version"
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

        // 实测在`project.afterEvaluate`前后，`sourceSet`数量不一样。
        // 但是如果不执行这次，`build.gradle`中的`sourceSets.main.scala.xxx`会报错。
        // 在这里执行之后，会 apply `build.gradle`中的`sourceSets.main.scala.xxx`的设置。
        //androidExtension.sourceSets.each { sourceSet -> }
        // 新的问题是：`sourceSet.java.srcDirs += sourceSet.scala.srcDirs`在`project.afterEvaluate{}`里会报错（查了源码没写错）：
        // Caused by: org.gradle.internal.typeconversion.UnsupportedNotationException: Cannot convert the provided notation to a File or URI: [/Users/weichou/git/bdo.cash/demo-material-3/app/src/githubDebug/scala].
        // The following types/formats are supported:
        //  - A String or CharSequence path, for example 'src/main/java' or '/usr/include'.  - A String or CharSequence URI, for example 'file:/usr/include'.  - A File instance.  - A Path instance.  - A Directory instance.  - A RegularFile instance.  - A URI or URL instance.  - A TextResource instance.
        // 所以改为如下写法：
        final sourceSetConfig = { sourceSet -> // androidTest, test, main
            LOG.info "$NAME_PLUGIN ---> sourceSetConfig:${sourceSet.name}"

            //final isTest = maybeIsTest(adjustSourceSetNameToMatchVariant(sourceSet.name, null))
            if (!resolveScalaSrcDirsToAndroidSourceSetsClosure(project, sourceSet, false)) {
                return // 已配置（至少`main`会重复配置）
            }
            // 根据`DefaultScalaSourceSet`中的这一句`scalaSourceDirectorySet.getFilter().include("**/*.java", "**/*.scala")`，表明是可以在 scala 目录下写 java 文件的。
            // 实测：虽然可以在 scala 目录写 java（并编译），但源码不能识别。但有该语句就能识别了(不用担心会串源码，有默认的过滤器)。
            sourceSet.java.srcDirs += sourceSet.scala.srcDirs // com.google.common.collect.SingletonImmutableSet<File> 或 RegularImmutableSet
            // java、scala 两个编译器都会编译输出，`./gradlew :app:assemble`时会报错：
            // >D8: Type `xxx.TestJavaXxx` is defined multiple times:
            //sourceSet.java.filter.excludes += "**/scala/**"
            sourceSet.java.filter.exclude { //org.gradle.api.internal.file.AttributeBasedFileVisitDetails it ->
                final path = it.file.path
                final sub = path.substring(0, path.length() - it.path.length())
                return sub.endsWith('/scala/')
            }
            if (sourceSet.java.srcDirs) LOG.info "${sourceSet.java.srcDirs} / ${sourceSet.java.srcDirs.class}"
            if (sourceSet.kotlin) {
                final ktSrc = sourceSet.kotlin.srcDirs // com.google.common.collect.RegularImmutableSet<File>
                // 同理：有了这句，kt 引用 scala 就不标红了。
                sourceSet.kotlin.srcDirs += sourceSet.scala.srcDirs // LinkedHashSet<File>
                // 既然…那就…（保险起见，让 scala 引用 java 目录下的文件不标红）。
                sourceSet.scala.srcDirs += ktSrc
                // TODO: 改为编译输出时过滤（见`evictCompileOutputForSrcTask()`），这样可以初步进行交叉编译（需要写 java 文件做中转）。但有个问题：如果 java 文件过多，会生成并删除许多问题。
                //  上面`sourceSet.java.filter.exclude`不用改（直接从源文件过滤），因为它最后编译，即使有依赖`/scala/`下的文件，这些先编译完的已经纳入到 JavaCompile 的 classpath 了。
                //  上面没有`sourceSet.kotlin.filter.exclude`说明 KotlinCompile 不会编译 java 文件。
                /*sourceSet.scala.filter.exclude { // 由于`ktSrc`包含 java 目录。实测：如果没有这句，会把 java 目录下的编译到 scala-classes 下，最终导致打包 jar 时某些 Xxx.class 重复。
                    final path = it.file.path
                    final sub = path.substring(0, path.length() - it.path.length())
                    return sub.endsWith('/java/')
                }*/
            }
            LOG.info "$NAME_PLUGIN ---> java.srcDirs:" + sourceSet.java.srcDirs
            LOG.info "$NAME_PLUGIN ---> scala.srcDirs:" + sourceSet.scala.srcDirs
            LOG.info "$NAME_PLUGIN ---> kotlin.srcDirs:" + (sourceSet.kotlin ? sourceSet.kotlin.srcDirs : null)
            LOG.info ''
        }
        androidExtension.sourceSets.whenObjectAdded { sourceSet -> // androidTest, main, test
            sourceSetConfig.call(sourceSet)
        }
        // 实测在 library 里，whenObjectAdded 不执行。
        androidExtension.sourceSets.each { sourceSet -> // ...
            sourceSetConfig.call(sourceSet)
        }
        final mainSourceSet = androidExtension.sourceSets.getByName(main)
        LOG.info ''
        LOG.info "$NAME_PLUGIN ---> mainSourceSet: $mainSourceSet"
        LOG.info ''
        LOG.info "|||||||||| |||||||||| |||||||||| |||||||||| |||||||||| AFTER EVALUATE |||||||||| |||||||||| |||||||||| |||||||||| ||||||||||"

        // 要保证`main`在`project.afterEvaluate{}`配置完成。否则实测会报错。
        sourceSetConfig.call(mainSourceSet)

        project.afterEvaluate {
            LOG.info ''
            ////////// ////////// ////////// ////////// ////////// ////////// ////////// ////////// ////////// ////////// ////////// ////////// //////////
            //printConfiguration(project, 'implementation')

            //dependencies {
            //    这里的`implementation`就是`Configuration`的名字，是在`Android`插件中定义的。
            //    所以需要什么就在`dependencies`中找什么，然后按照下面的代码示例查找。
            //    implementation "androidx.core:core-ktx:$ktx.Version"
            //    implementation "org.scala-lang:scala-library:$scala2.Version"
            //}
            // 为了让`ScalaCompile`能够找到`Scala`的版本，需要在`ScalaCompile`的`classpath`中添加包含"library"的 jar。详见：`scalaRuntime.inferScalaClasspath(compile.getClasspath())`。
            // `classpath`需要`FileCollection`，而`Configuration`继承自`FileCollection`，所以可以直接使用`Configuration`。
            // 具体写在下边`x.register("xxx", ScalaCompile)`。

            ////////// ////////// ////////// ////////// ////////// ////////// ////////// ////////// ////////// ////////// ////////// ////////// //////////
            final variantsAll = androidExtension.testVariants + (isLibrary ? androidExtension.libraryVariants : androidExtension.applicationVariants)
            final variantsNames = new HashSet<String>()
            final variantsMap = new HashMap<String, Object>()
            final variantSourceSetMap = new HashMap<String, Object>()
            variantsAll.each { variant ->
                variantsNames.add(variant.name)
                variantsMap.put(variant.name, variant)
            }

            LOG.info ''
            LOG.info "$NAME_PLUGIN ---> variantsNames: ${variantsNames.join(", ")}"
            LOG.info ''

            androidExtension.sourceSets.each { sourceSet -> // androidTest, test, main
                LOG.info "$NAME_PLUGIN <<<===>>> sourceSet.name:${sourceSet.name}, sourceSet:$sourceSet"

                ////////// ////////// ////////// ////////// ////////// ////////// ////////// ////////// ////////// ////////// ////////// ////////// //////////
                // 实测发现：`sourceSet.name`和`variant.name`有些不同：
                // 例如：`sourceSet.name`是`androidTestGithubDebug`，而`variant.name`是`githubDebugAndroidTest`。但已有的`compileXxxJava/Kotlin` task 名字是以`variant.name`为准的。
                // 所以需要统一。
                LOG.info "$NAME_PLUGIN ---> contains:${variantsNames.contains(sourceSet.name)}"
                final srcSetNameMatchVariant = adjustSourceSetNameToMatchVariant(sourceSet.name, variantsNames)
                if (!srcSetNameMatchVariant) return

                variantSourceSetMap.put(srcSetNameMatchVariant, sourceSet)

                final variant = variantsMap[srcSetNameMatchVariant] // 没有名为`main`的
                final isTest = maybeIsTest(srcSetNameMatchVariant)
                //ScalaRuntime scalaRuntime = project.extensions.getByName(SCALA_RUNTIME_EXTENSION_NAME)

                configureScalaCompile(project, variant, sourceSet, mainSourceSet, androidExtension, srcSetNameMatchVariant, isLibrary, isTest)
            }

            LOG.info "||||||||| |||||||||| |||||||||| link all variants depends on |||||||||| |||||||||| ||||||||||"
            def possibleVariant
            variantsAll.each { variant ->
                //variant = "$flavor$buildType" // e.g. "googleplayRelease", "githubDebug".
                //"compile${variant.name.capitalize()}Scala" // `.capitalize()`首字母大写
                LOG.info ''

                final isTest = maybeIsTest(variant.name)
                if (!possibleVariant && !isTest) possibleVariant = variant
                final sourceSet = variantSourceSetMap[variant.name]

                linkScalaCompileDependsOn(project, scalroid, androidPlugin, androidExtension, workDir, variant, sourceSet, isLibrary, isTest)

                LOG.info "<<<<<<<<<<<============ ${variant.name} DONE ===============>>>>>>>>>>>>>>>>>>>"
            }

            ////////// ////////// ////////// ////////// ////////// ////////// ////////// ////////// ////////// ////////// ////////// ////////// //////////
            // 这一步需要等上面`linkScalaCompileDependsOn()`触发`ScalaCompile`配置完成，才能继续。
            // 但是没有名为`main`的 variant，也就没能正确`linkScalaCompileDependsOn()`，所以要找个`possibleVariant`。
            configureScaladocAndIncrementalAnalysis(project, mainSourceSet, possibleVariant, isLibrary)
        }
    }

    void configureScaladocAndIncrementalAnalysis(Project project, mainSourceSet, possibleVariant, boolean isLibrary) {
        final mainScalaTaskName = genScalaCompileTaskName(possibleVariant.name) // mainSourceSet.name
        final TaskProvider<ScalaCompile> compileScala = project.tasks.withType(ScalaCompile).named(mainScalaTaskName)
        final mainScalaCompile = compileScala.get()
        project.configurations.incrementalScalaAnalysisElements.outgoing.artifact(mainScalaCompile
                .analysisMappingFile) { builtBy(compileScala) }
        configureScaladoc(project, mainSourceSet, mainScalaCompile)
    }

    // 把 scalroid 加入到 android 下面。可以这样写：
    // android {
    //   scalroid {
    //     message = 'Hi'
    //     greeter = 'Gradle'
    //   }
    // }
    private void addPluginExtensionToAndroidClosure(android, scalroid) {
        android.convention.plugins.put(NAME_PLUGIN, scalroid) // 正规的写法
        // 等同于（详见`InvokerHelper.invokeMethod()`）：
        // `android.metaClass."get${NAME_PLUGIN.capitalize()}" = scalroid`
        android.metaClass."$NAME_PLUGIN" = scalroid // 加这个的原因同下面
    }

    //scalroid {
    //    scala.zincVersion = '1.3.5'
    //    ...
    //}
    private void addScalaPluginExtensionToScalroidClosure(scalroid, scala) {
        scalroid.convention.plugins.put(NAME_SCALA_EXTENSION, scala) // 正规的写法
        // 但如果不加这个，没法这样写（`build.gradle`和这里的代码都一样）：
        // `scalroid.scala.zincVersion = xxx`
        // 只能这样：
        // scalroid {
        //   scala.zincVersion.set(xxx)
        // }
        scalroid.metaClass."$NAME_SCALA_EXTENSION" = scala
    }

    // [evaluated] 表示是否在`project.afterEvaluate{}`中执行。
    private boolean resolveScalaSrcDirsToAndroidSourceSetsClosure(Project project, sourceSet, boolean evaluated) {
        LOG.info "$NAME_PLUGIN ---> [resolveScalaSrcDirsToAndroidSourceSetsClosure]sourceSet.name:${sourceSet.name}, displayName:${sourceSet.displayName}"
        LOG.info "$NAME_PLUGIN ---> [resolveScalaSrcDirsToAndroidSourceSetsClosure]sourceSet.extensions:${sourceSet.extensions}"
        //org.gradle.internal.extensibility.DefaultConvention

        // 对于不同的`sourceSet`，第一次肯定没有值。
        if (sourceSet.extensions.findByName("scala")) return false

        final displayName = sourceSet.displayName //(String) InvokerHelper.invokeMethod(sourceSet, "getDisplayName", null)
        //Convention sourceSetConvention = sourceSet.convention //(Convention) InvokerHelper.getProperty(sourceSet, "convention")
        DefaultScalaSourceSet scalaSourceSet = new DefaultScalaSourceSet(displayName, factory)
        final SourceDirectorySet scalaDirSet = scalaSourceSet.scala
        // 这句`约定（convention）`的作用是添加：
        // sourceSets { main { scala.srcDirs += ['src/main/java'] } ...}
        sourceSet.convention.plugins.put("scala", scalaSourceSet)
        sourceSet.extensions.add(ScalaSourceDirectorySet, "scala", scalaDirSet)
        scalaDirSet.srcDir(project.file("src/${sourceSet.name}/scala"))

        // Explicitly capture only a FileCollection in the lambda below for compatibility with configuration-cache.
        FileCollection scalaSource = scalaDirSet
        sourceSet.resources.filter.exclude(spec(element -> scalaSource.contains(element.file)))
        return true
    }

    private void configureScalaCompile(Project project, @Nullable variant, sourceSet, mainSourceSet, androidExtension, String src$vaName, boolean isLibrary, boolean isTest) {
        final ScalaSourceDirectorySet scalaDirectorySet = sourceSet.extensions.getByType(ScalaSourceDirectorySet)
        Configuration classpathBySourceSetImpl = project.configurations.getByName(sourceSet.implementationConfigurationName)
        //printConfiguration(project, sourceSet.implementationConfigurationName)

        ScalaSourceDirectorySet mainScalaDirSet
        Configuration mainClasspathImpl = null
        if (sourceSet != mainSourceSet) {
            mainScalaDirSet = mainSourceSet.extensions.getByType(ScalaSourceDirectorySet)
            // 等同于`project.configurations.implementation`
            mainClasspathImpl = project.configurations.getByName(mainSourceSet.implementationConfigurationName)

            LOG.info "$NAME_PLUGIN ---> [configureScalaCompile]mainScalaDirSet.name:${mainScalaDirSet.name}, mainScalaDirSet.displayName:${mainScalaDirSet.displayName}"
            LOG.info ''
        }
        // 打印查看，各 variant 的 implementation 都包含了 main 的（但是 {variant}AndroidTest 没有，所以还是要加上），且包含 api, compileOnly 等：
        // xxxConfig.hierarchy.each { Configuration it ->
        //     println " -- ${it}"
        //     it.attributes.each { attrs -> } …
        // 实测：有时不在`config.extendsFrom()`的第一层不行，所以得展开（`.getHierarchy()`）。
        def classpathConfigs = classpathBySourceSetImpl.hierarchy.toList() + (mainClasspathImpl ? mainClasspathImpl.hierarchy.toList() : [])
        classpathConfigs.removeIf { !it || it.dependencies.isEmpty() }
        classpathConfigs = classpathConfigs.toSet()
        LOG.info "$NAME_PLUGIN ---> [configureScalaCompile]sourceSet.name:${sourceSet.name}, classpathConfigs.size:${classpathConfigs.size()}"

        Configuration incrementalAnalysis = project.configurations.create("incrementalScalaAnalysisFor${src$vaName.capitalize()}")
        incrementalAnalysis.description = "Incremental compilation analysis files for ${sourceSet.displayName}"
        incrementalAnalysis.visible = false
        incrementalAnalysis.canBeResolved = true
        incrementalAnalysis.canBeConsumed = false
        incrementalAnalysis.extendsFrom = classpathConfigs
        incrementalAnalysis.attributes.attribute(USAGE_ATTRIBUTE, factory.named(Usage, "incremental-analysis"))

        project.tasks.register(genScalaCompileTaskName(src$vaName), ScalaCompile) { ScalaCompile scalaCompile ->
            LOG.info "$NAME_PLUGIN ---> [configureScalaCompile]compileTaskName:${scalaCompile.name}, variant:${variant ? variant.name : null}, isLibrary:$isLibrary, isTest:$isTest"

            final compilerClasspath = project.configurations.create("${src$vaName}ScalaCompileClasspath").setExtendsFrom(classpathConfigs)

            scalaCompile.classpath = obtainWithSetsOnlyCanBeResolvedVariantSelectionAttributes(project, scalaCompile, compilerClasspath, src$vaName, isTest)
            // TODO: 实测要把`android.jar`也加入 classpath，否则如果 scala 代码间接（或直接）引用如`android.app.Activity`，会报如下错误：
            // [Error] /Users/.../demo-material-3/app/src/main/scala/com/example/demomaterial3/Test2.scala:9:7: Class android.app.Activity not found - continuing with a stub. one error found...
            scalaCompile.classpath += androidExtension.bootClasspathConfig.mockableJarArtifact // mock `android.jar`

            ////////// ////////// ////////// ////////// ////////// ////////// ////////// ////////// ////////// ////////// ////////// ////////// //////////
            scalaCompile.source(scalaDirectorySet)
            // TODO: 不编译的终极原因（`./gradlew :app:compileGithubDebugScala --info`）：
            //  Skipping task ':app:compileGithubDebugScala' as it has no source files and no previous output files.
            // 另外，`isTest`的原因：
            // `:app:compile{variant}AndroidTestJavaWithJavac`本来就依赖于`:app:compile{variant}JavaWithJavac`，所以就不用在这里手动加了。
            if (!isTest && mainScalaDirSet) scalaCompile.source(mainScalaDirSet)
            ////////// ////////// ////////// ////////// ////////// ////////// ////////// ////////// ////////// ////////// ////////// ////////// //////////

            scalaCompile.description = "Compiles the ${scalaDirectorySet}."
            //scalaCompile.javaLauncher.convention(getToolchainTool(project, JavaToolchainService::launcherFor))
            scalaCompile.analysisMappingFile.set(project.layout.buildDirectory.file("scala/compilerAnalysis/${scalaCompile.name}.mapping"))

            // Cannot compute at task execution time because we need association with source set
            IncrementalCompileOptions incrementalOptions = scalaCompile.scalaCompileOptions.incrementalOptions
            incrementalOptions.analysisFile.set(project.layout.buildDirectory.file("scala/compilerAnalysis/${scalaCompile.name}.analysis"))
            incrementalOptions.classfileBackupDir.set(project.layout.buildDirectory.file("scala/classfileBackup/${scalaCompile.name}.bak"))

            scalaCompile.analysisFiles.from(incrementalAnalysis.incoming.artifactView {
                lenient(true)
                componentFilter(new Spec<ComponentIdentifier>() {
                    boolean isSatisfiedBy(ComponentIdentifier element) { return element instanceof ProjectComponentIdentifier }
                })
            }.files)
            scalaCompile.dependsOn(scalaCompile.analysisFiles)

            // 目录与 kotlin 保持一致（原本下面要用到，但没用，已删）。
            //scalaDirectorySet.destinationDirectory.convention(project.layout.buildDirectory.dir("tmp/scala-classes/${src$vaName}"))
            scalaCompile.destinationDirectory.convention(/*scalaDirectorySet.destinationDirectory*/ project.layout.buildDirectory.dir("tmp/scala-classes/${src$vaName}"))
        }
    }

    private Configuration obtainWithSetsOnlyCanBeResolvedVariantSelectionAttributes(Project project, Task scalaCompile, Configuration compilerClasspath, String src$vaName, boolean isTest) {
        //printClasspathOnTaskDoFirst(project, scalaCompile, compilerClasspath, src$vaName, null)

        // 详情参见：
        // https://docs.gradle.org/current/userguide/variant_model.html#sec:variant-visual
        // https://docs.gradle.org/current/userguide/variant_attributes.html#sec:abm_algorithm
        // 之前卡了几周的问题在这：
        // https://docs.gradle.org/current/userguide/declaring_dependencies.html#sec:resolvable-consumable-configs
        // https://docs.gradle.org/current/userguide/variant_attributes.html#sec:abm_disambiguation_rules
        // 由于一直不知道这两个属性起什么作用，从而被忽略。但默认值均为 true，导致：
        // 1. 既可以解析依赖（即 consumer 角色，根据设置的 attributes 选择依赖的 variant）；
        // 2. 又可以被消化，也就是候选者（candidate）。
        // 由于下面设置的 attributes 无法区分上游的被依赖者是哪个 project，所以会总是设置了兼容的 attributes 从而
        // 被上游错误地解析到。所以要么出现歧义（disambiguation），要么编译时出现错误。歧义问题表现在依赖的 sub-project，在
        //     api project(path: ':annoid', configuration: 'debugRuntimeElements')
        // 配置中，configuration 通常不设置，即
        //     api project(':annoid')
        // 默认就是`default`，等同于
        //     api project(path: ':annoid', configuration: 'default')
        // 就会报错。解决方法及其简单：
        compilerClasspath.canBeResolved = true
        compilerClasspath.canBeConsumed = false
        /*
        // 运行`./gradlew :annoid:outgoingVariants`可以看到：
        //   （`./gradlew :assoid:resolvableConfigurations`）

        > Task :annoid:outgoingVariants
        --------------------------------------------------
        Variant debugApiElements  // 有很多
        --------------------------------------------------
        API elements for debug

        Capabilities
            - hobby.wei.c.anno:annoid:1.2.1 (default capability)
        Attributes
            - com.android.build.api.attributes.AgpVersionAttr          = 7.4.0
            - com.android.build.api.attributes.BuildTypeAttr           = debug
            - com.android.build.gradle.internal.attributes.VariantAttr = debug
            - org.gradle.category                                      = library
            - org.gradle.jvm.environment                               = android
            - org.gradle.usage                                         = java-api
            - org.jetbrains.kotlin.platform.type                       = androidJvm

        Secondary Variants (*)

        --------------------------------------------------
        Secondary Variant android-aidl
        --------------------------------------------------
        ...

        --------------------------------------------------
        Secondary Variant android-classes-jar
        --------------------------------------------------
        Attributes
            - ...
        Artifacts
            - build/intermediates/compile_library_classes_jar/debug/classes.jar (artifactType = android-classes-jar)
        */
        // TODO:
        //  这里只需要把重点区别标识出来，也就是 Secondary Variants，有很多。这里需要的是
        //  `artifactType = android-classes-jar`，还要个
        //  `com.android.build.api.attributes.BuildTypeAttr = debug/release`
        //  即可。

        //final attrUsage = 'org.gradle.usage' // java-runtime/java-api
        //final attrCategory = 'org.gradle.category' // library
        //final attrJvmEnv = '.jvm.environment' // android
        //final attrPlatType = '.platform.type' // androidJvm
        final attrBuildType = '.attributes.BuildType' // debug/release
        //final attrVariant = '.attributes.Variant' // debug/release/xxxDebug/xxxRelease
        //final attrAgpVersion = '.attributes.AgpVersion' // 7.4.0
        //final attrLibElements = LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE // 'org.gradle.libraryelements' // classes
        final List forceAttrs = [attrBuildType] //attrUsage, attrCategory, attrJvmEnv, attrPlatType]

        final CLASSES_JAR = 'android-classes-jar'
        final artifactType = ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE
        //compilerClasspath.attributes.attribute(USAGE_ATTRIBUTE, factory.named(Usage, JAVA_RUNTIME))
        //compilerClasspath.attributes.attribute(CATEGORY_ATTRIBUTE, factory.named(Category, LIBRARY))
        // com.android.build.gradle.internal.publishing.AndroidArtifacts.ArtifactType.CLASSES_JAR.type/AAR_OR_JAR.type
        compilerClasspath.attributes.attribute(artifactType, CLASSES_JAR)

        //final isDebug = [debug, debug.capitalize()].any { src$vaName.contains(it) }
        final apiElements = project.configurations.findByName("${isTest ? parseMainVariantNameForTest(src$vaName) : src$vaName}ApiElements")
        final runtimeElements = project.configurations.findByName("${isTest ? parseMainVariantNameForTest(src$vaName) : src$vaName}RuntimeElements")
        final elements = runtimeElements ? runtimeElements : apiElements
        //src$vaName == main 时，elements = null
        if (elements) elements.attributes.each { attrs ->
            attrs.keySet().each { attr ->
                final value = attrs.getAttribute(attr)
                LOG.info "$NAME_PLUGIN ---> [setClasspathAttributes]attribute > ${attr.name} -> ${value} / ${value.class}"
                if (forceAttrs.any { attr.name.contains(it) }) {
                    LOG.info "$NAME_PLUGIN ---> [setClasspathAttributes]accept > ${attr.name} -> ${value}"
                    compilerClasspath.attributes.attribute(attr, value)
                }
            }
        }
        return compilerClasspath
    }

    private void linkScalaCompileDependsOn(Project project, ScalroidExtension scalroid, Plugin androidPlugin, androidExtension, File workDir, variant, sourceSet, boolean isLibrary, boolean isTest) {
        //LOG.info "$NAME_PLUGIN ---> [linkScalaCompileDependsOn]androidPlugin:${androidPlugin}" // com.android.build.gradle.AppPlugin@8ab260a
        //LOG.info "$NAME_PLUGIN ---> [linkScalaCompileDependsOn]workDir:${workDir.path}" // /Users/{PATH-TO-}/demo-material-3/app/build/scalroid
        LOG.info "$NAME_PLUGIN ---> [linkScalaCompileDependsOn]variant:${variant.name}" // githubDebug

        final javaTaskName = genJavaCompileTaskName(variant.name)
        final scalaTaskName = genScalaCompileTaskName(variant.name)
        final kotlinTaskName = genKotlinCompileTaskName(variant.name)
        final buildConfigTaskName = genBuildConfigTaskName(variant.name)
        final processResourcesTaskName = genProcessResourcesTaskName(variant.name)
        final rFileTaskName = genRFileTaskName(variant.name)
        final dataBindingGenBaseTaskName = genDataBindingGenBaseTaskName(variant.name)

        final JavaCompile javaCompile = project.tasks.findByName(javaTaskName)
        if (javaCompile) {
            // 获取前面已经注册的`scalaCompileTask`。见`project.tasks.register()`文档。
            project.tasks.withType(ScalaCompile).getByName(scalaTaskName) { ScalaCompile scalaCompile ->
                LOG.info "$NAME_PLUGIN ---> [linkScalaCompileDependsOn]javaCompile.destinationDirectory:${javaCompile.destinationDirectory.orNull}"

                javaCompile.dependsOn scalaCompile

                // 目录与 kotlin 保持一致（前面已经设置默认值了）
                //scalaCompile.destinationDirectory.set(project.layout.buildDirectory.dir("tmp/scala-classes/${variant.name}"))
                scalaCompile.sourceCompatibility = javaCompile.sourceCompatibility
                scalaCompile.targetCompatibility = javaCompile.targetCompatibility
                // Unexpected javac output: 警告: [options] 未与 -source 8 一起设置引导类路径
                scalaCompile.options.bootstrapClasspath = javaCompile.options.bootstrapClasspath
                scalaCompile.options.encoding = javaCompile.options.encoding
                // scalaCompile.scalaCompileOptions 可以在`build.gradle`脚本中配置
                //scalaCompile.scalaCompileOptions.encoding = scalaCompile.options.encoding

                final processRes = project.tasks.findByName(processResourcesTaskName)
                final rFile = project.tasks.findByName(rFileTaskName)
                final dataBinding = project.tasks.findByName(dataBindingGenBaseTaskName)
                if (processRes) {
                    scalaCompile.dependsOn processRes
                    scalaCompile.classpath += project.files(processRes.outputs.files.find { it.path.endsWith("${variant.name}/R.jar") })
                } else if (rFile) {
                    scalaCompile.dependsOn rFile
                    scalaCompile.classpath += project.files(rFile.outputs.files.find { it.path.endsWith("${variant.name}/R.jar") })
                }
                // 把生成的`BuildConfig`加入依赖。同理，还可以加入别的。
                project.tasks.getByName(buildConfigTaskName) { Task buildConfig -> // ...
                    scalaCompile.source(buildConfig)
                    evictCompileOutputForSrcTask(project, scalaCompile, buildConfig, scalroid, 'src/main/java/', 'src/main/kotlin/')
                }
                if (dataBinding) {
                    scalaCompile.source(dataBinding.outputs.files.filter { it.path.contains("/generated/") && it.path.contains("/${variant.name}/") })
                    evictCompileOutputForSrcTask(project, scalaCompile, dataBinding, null /*置为 null，避免重复计算。*/)
                }

                final kotlinCompile = project.tasks.findByName(kotlinTaskName)
                if (kotlinCompile) {
                    //LOG.info "$NAME_PLUGIN ---> [linkScalaCompileDependsOn]kotlinCompile:${kotlinCompile} / ${kotlinCompile.class}" // org.jetbrains.kotlin.gradle.tasks.KotlinCompile

                    wireScalaTasks(project, scalroid, variant, project.tasks.named(scalaTaskName), project.tasks.named(javaTaskName), project.tasks.named(kotlinTaskName), isLibrary, isTest)
                } else {
                    LOG.warn "$NAME_PLUGIN ---> [linkScalaCompileDependsOn]kotlinCompile:null"
                }
                // `:app:compile{variant}AndroidTestJavaWithJavac`本来就依赖于`:app:compile{variant}JavaWithJavac`，而
                // `:app:compile{variant}JavaWithJavac`依赖于`:app:compile{variant}Scala`，所以就不用在这里手动加了（加也无妨）。
                if (isTest) {
                    LOG.info "$NAME_PLUGIN ---> [linkScalaCompileDependsOn]isTest:$isTest"
                    final scalaMainVarTaskName = genScalaCompileTaskName(parseMainVariantNameForTest(variant.name))
                    project.tasks.withType(ScalaCompile).getByName(scalaMainVarTaskName) { ScalaCompile scalaCompileMainVar ->
                        LOG.info "$NAME_PLUGIN ---> [linkScalaCompileDependsOn]scalaCompileMainVar.name:${scalaCompileMainVar.name}"
                        scalaCompile.dependsOn scalaCompileMainVar
                    }
                }
            }
        } else {
            LOG.warn "$NAME_PLUGIN ---> [linkScalaCompileDependsOn]javaCompile:null"
        }
    }

    private void evictCompileOutputForSrcTask(Project project, AbstractCompile dest, Task src, @Nullable ScalroidExtension scalroid, String... srcDirs) {
        // 无法创建针对输出的过滤器（而输入默认携带`task.exclude()`），试过所以方法（包括搜索文档）。因为`task.outputs.xxx`基本都是不可变的，意味着
        // 调用某的方法仅返回一个值，无法设置进去从而起作用。
        //PatternFilterable patternFilter = patternSetFactory.create()
        //scalaCompile.outputs.files.asFileTree.matching(patternFilter)
        /*dest.doFirst {
            src.outputs.files.files.each {
                println "[src.outputs] ??? | $it"
            }
            dest.source.files.each {
                println "[dest.source] == | $it"
            }
        }*/
        final parentPaths = []
        final excludePaths = []
        final parentPathToLens = [:]
        dest.doFirst {
            if (src) {
                parentPaths.addAll(src.outputs.files.files.findAll { it.isDirectory() }.collect { it.path }.sort { (o1, o2) -> o1.length() - o2.length() }.toSet().toList())
                final temp = []; temp.addAll(parentPaths)
                for (int i = 0; i < temp.size(); i++) {
                    for (int j = i + 1; j < temp.size(); j++) { // 由于已经排序了，后面的比较长。
                        if (temp[j].startsWith(temp[i])) parentPaths.remove(temp[j])
                    }
                }
                // 通过上述算法，`parentPaths`已经剩下每个不同目录的最短`目录的 path`。
                parentPaths.each { path -> parentPathToLens.put(path, path.length() + (path.endsWith('/') ? 0 : 1)) }
                LOG.info "$NAME_PLUGIN ---> [evictCompileOutputForSrcTask]parentPathToLens:${parentPathToLens}"
            }
            final tempDirs = []; if (scalroid) tempDirs.addAll(scalroid.javaDirsExcludes.get()); tempDirs.addAll(srcDirs)
            excludePaths.addAll(tempDirs.collect { it.endsWith('/') ? it : (it + '/') }.toSet().toList())
            LOG.info "$NAME_PLUGIN ---> [evictCompileOutputForSrcTask]excludePaths:${excludePaths}"
        }
        dest.doLast {
            final destDir = dest.destinationDirectory.asFile.get().path
            final destLen = destDir.length() + (destDir.endsWith('/') ? 0 : 1)
            final projDir = project.layout.projectDirectory.asFile.path
            final projLen = projDir.length() + (projDir.endsWith('/') ? 0 : 1)

            final allSrcMatchTask = dest.source.files.findAll { file -> parentPaths.any { file.path.startsWith(it) } }
            final allSrcMatchDirs = dest.source.files.findAll { file -> excludePaths.any { file.path.indexOf(it, projLen) > 0 } }

            final pkgNamePaths = allSrcMatchTask
                    .collect { file -> file.path.substring(parentPathToLens.find { file.path.startsWith(it.key) }.value) }
                    .collect { final i = it.lastIndexOf('/'); final j = it.lastIndexOf('.'); i < j && j > 0 ? it.substring(0, j) : it }
            LOG.info "$NAME_PLUGIN ---> [evictCompileOutputForSrcTask]packageAndNames:${pkgNamePaths}"

            final pkgNameExcludes = allSrcMatchDirs
                    .collect { file -> int i = -1; final path = excludePaths.find { i = file.path.indexOf(it, projLen); i > 0 }; assert i > 0; file.path.substring(i + path.length()) }
                    .collect { final i = it.lastIndexOf('/'); final j = it.lastIndexOf('.'); i < j && j > 0 ? it.substring(0, j) : it }
            LOG.info "$NAME_PLUGIN ---> [evictCompileOutputForSrcTask]packageNameExcludes:${pkgNameExcludes}"

            dest.outputs.files.asFileTree.each { File file ->
                final hit = (pkgNamePaths + pkgNameExcludes).any { pkgName ->
                    //file.path.substring(destLen) == (pkgName + '.class') // 可能后面跟的不是`.class`而是`$1.class`、`$xxx.class`。
                    //file.path.substring(destLen).startsWith(pkgName) // 改写如下：
                    file.path.indexOf(pkgName, destLen) == destLen && file.path.indexOf('/', destLen + pkgName.length()) < 0
                            //hobby/wei/c/L$3.class
                            //hobby/wei/c/LOG.class
                            && (file.path.indexOf(pkgName + '.', destLen) == destLen || file.path.indexOf(pkgName + '$', destLen) == destLen)
                }
                if (hit) {
                    LOG.info "$NAME_PLUGIN ---> [evictCompileOutputForSrcTask] ^^^ HIT:$file"
                    file.delete()
                } else {
                    LOG.info "$NAME_PLUGIN ---> [evictCompileOutputForSrcTask] NOT HIT:$file"
                }
            }
        }
    }

    private defaultSourceSet(Project project, variant) {
        // 就是各个 variant 对应的 SourceSet。
        // debugAndroidTest, debug, release
        // githubDebugAndroidTest, googleplayDebugAndroidTest, githubDebug, googleplayDebug, githubRelease, googleplayRelease
        return kotlinJvmAndroidCompilation(project, variant).defaultSourceSet
    }

    private kotlinAndroidTarget(Project project) {
        // 参见`org.jetbrains.kotlin.gradle.plugin.KotlinAndroidPlugin`开头的`(project.kotlinExtension as KotlinAndroidProjectExtension).target = it`
        // 根据源码分析，这里已不需要进行`.castIsolatedKotlinPluginClassLoaderAware()`，也没法直接调用（其目的
        // 是过早地发现错误并给出详细的建议，见`IsolatedKotlinClasspathClassCastException`）。
        final kotlinExtension = project.extensions.getByName(NAME_KOTLIN_EXTENSION) // KotlinAndroidProjectExtension_Decorated
        return kotlinExtension.target // KotlinAndroidTarget
    }

    private kotlinJvmAndroidCompilation(Project project, variant) {
        return kotlinAndroidTarget(project).compilations.getByName(variant.name) // KotlinJvmAndroidCompilation
    }

    private void wireScalaTasks(Project project, ScalroidExtension scalroid, variant, TaskProvider scalaTask, TaskProvider javaTask, TaskProvider kotlinTask, boolean isLibrary, boolean isTest) {
        final compilation = kotlinJvmAndroidCompilation(project, variant)
        final outputs = compilation.output.classesDirs // ConfigurableFileCollection
        //LOG.info "$NAME_PLUGIN ---> [wireScalaTasks]outputs:${outputs.class}, it.files:${outputs.files}"
        outputs.from(scalaTask.flatMap { it.destinationDirectory })
        //final outputs1 = compilation.output.classesDirs
        //LOG.info "$NAME_PLUGIN ---> [wireScalaTasks]outputs1:${outputs1.class}, it.files:${outputs1.files}"

        // 写法参见`org.jetbrains.kotlin.gradle.plugin.Android25ProjectHandler`的`wireKotlinTasks()`。
        // 如果这样写`project.files(scalaTask.get().destinationDirectory)`会导致 Task 的循环依赖。
        final javaOuts = project.files(project.provider([call: { javaTask.get().destinationDirectory.get().asFile }] as Callable))
        final scalaOuts = project.files(project.provider([call: { scalaTask.get().destinationDirectory.get().asFile }] as Callable))
        final kotlinOuts = project.files(project.provider([call: { kotlinTask.get().destinationDirectory.get().asFile }] as Callable))
        LOG.info "$NAME_PLUGIN ---> [wireScalaTasks]javaOuts:${javaOuts}, it.files:${javaOuts.files}"
        LOG.info "$NAME_PLUGIN ---> [wireScalaTasks]scalaOuts:${scalaOuts}, it.files:${scalaOuts.files}"
        LOG.info "$NAME_PLUGIN ---> [wireScalaTasks]kotlinOuts:${kotlinOuts}, it.files:${kotlinOuts.files}"

        // 这句的作用是便于分析出任务依赖关系（In order to properly wire up tasks），详见`Object registerPreJavacGeneratedBytecode(FileCollection)`文档。
        // 这句可以不要以欺骗依赖图生成循环依赖。
//        scalaOuts.builtBy(scalaTask)
        //variant.registerJavaGeneratingTask(scalaTask, scalaTask.get().source.files)
        //variant.registerJavaGeneratingTask(kotlinTask, kotlinTask.get().sources.files)

        //final outsNew = javaOuts + kotlinOuts //.from(kotlinOuts)
        //final classpathKey = variant.registerPreJavacGeneratedBytecode(outsNew)
        //LOG.info "$NAME_PLUGIN ---> [wireScalaTasks]classpathKey:${classpathKey} / ${classpathKey.class}" // java.lang.Object@2faa3212 / class java.lang.Object

        // 根据根据源码分析，下面`classpathKey`可以不传（即：null）。
        // 但不传的话，拿到的`compileClasspath`不一样，具体表现在：`传/不传`的循环依赖不同。
        // 根据源码分析，拿到的返回值包含`variant.getGeneratedBytecode(classpathKey)`的返回值，该值包含
        // 参数`classpathKey`在注册（`variant.registerPreJavacGeneratedBytecode(fileCol)`）之前传入（即`fileCol`）的所有值。
        // 简单来说，每次调用下面的方法，返回值都包含[除本次注册外]之前注册时入参的所有`FileCollection`。
        // 而如果没有参数，则包含所有已注册的值。
//        final compileClasspath = variant.getCompileClasspath(/*classpathKey*/)
        // org.gradle.api.internal.file.collections.DefaultConfigurableFileCollection
        //LOG.info "$NAME_PLUGIN ---> [wireScalaTasks]variant.getCompileClasspath(classpathKey):${compileClasspath} / ${compileClasspath.class}"
        // TODO:
        //  java.lang.RuntimeException: Configuration 'githubDebugCompileClasspath' was resolved during configuration time.
        //  This is a build performance and scalability issue.
        //  scalroid ---> [wireScalaTasks]variant.getCompileClasspath(classpathKey):[/Users/weichou/git/bdo.cash/demo-material-3/app/build/intermediates/compile_and_runtime_not_namespaced_r_class_jar/
        //    githubDebug/R.jar, /Users/weichou/.gradle/caches/transforms-3/893b9b5a0019ab2d13f957bcf2fabcb9/transformed/viewbinding-7.3.1-api.jar, /Users/weichou/.gradle/caches/transforms-3/3b9fa4710e9e16ecd783cc23f2a62967/transformed/navigation-ui-ktx-2.5.0-api.jar, /Users/weichou/.gradle/caches/transforms-3/b4c5cd6f5d69a09c90e3ea53830c48f5/transformed/navigation-ui-2.5.0-api.jar, /Users/weichou/.gradle/caches/transforms-3/7c5ae5c220196941122f9db4d7a639bc/transformed/material-1.7.0-api.jar, /Users/weichou/.gradle/caches/transforms-3/a2622ad63284a4fbebfb584b9b851884/transformed/appcompat-1.5.0-api.jar, /Users/weichou/.gradle/caches/transforms-3/6c3948d45ddaf709ba26745189eab999/transformed/viewpager2-1.0.0-api.jar, /Users/weichou/.gradle/caches/transforms-3/2a8d540a7e825c31c133e1550351db89/transformed/navigation-fragment-ktx-2.5.0-api.jar, /Users/weichou/.gradle/caches/transforms-3/d9907136fb1ec2be0470c0c515d25b44/transformed/navigation-fragment-2.5.0-api.jar, /Users/weichou/.gradle/caches/transforms-3/6eaf265bb3918c4fd1dce0869a434f72/transformed/fragment-ktx-1.5.0-api.jar, /Users/weichou/.gradle/caches/transforms-3/3ff5ce784625b6b2d8a5c8ed94aa647c/transformed/fragment-1.5.0-api.jar, /Users/weichou/.gradle/caches/transforms-3/27c0c7f932da50fcd00c251ed8922e6d/transformed/navigation-runtime-ktx-2.5.0-api.jar, /Users/weichou/.gradle/caches/transforms-3/b010a8c66f1d64f1d21c27bb1beb821c/transformed/navigation-runtime-2.5.0-api.jar, /Users/weichou/.gradle/caches/transforms-3/f151321734e20d7116485bb3ab462df6/transformed/activity-ktx-1.5.0-api.jar, /Users/weichou/.gradle/caches/transforms-3/21307ac95e78f6bab6ef32fc6c8c8597/transformed/activity-1.5.0-api.jar, /Users/weichou/.gradle/caches/transforms-3/b7a04d7ac46f005b9d948413fa63b076/transformed/navigation-common-ktx-2.5.0-api.jar, /Users/weichou/.gradle/caches/transforms-3/7a98affa0f1b253f31ac3690fb7b577b/transformed/navigation-common-2.5.0-api.jar, /Users/weichou/.gradle/caches/transforms-3/49780de7b782ff560647221ad2cc9d58/transformed/lifecycle-viewmodel-savedstate-2.5.0-api.jar, /Users/weichou/.gradle/caches/transforms-3/ec53663f906b415395ef3a78e7add5aa/transformed/lifecycle-viewmodel-ktx-2.5.0-api.jar, /Users/weichou/.gradle/caches/transforms-3/498d61dce25ec5c15c1a4140b70f1b13/transformed/lifecycle-runtime-ktx-2.5.0-api.jar, /Users/weichou/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.6.1/97fd74ccf54a863d221956ffcd21835e168e2aaa/kotlinx-coroutines-core-jvm-1.6.1.jar, /Users/weichou/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlinx/kotlinx-coroutines-android/1.6.1/4e61fcdcc508cbaa37c4a284a50205d7c7767e37/kotlinx-coroutines-android-1.6.1.jar, /Users/weichou/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib-jdk8/1.7.20/eac6656981d9d7156e838467d2d8d79093b1570/kotlin-stdlib-jdk8-1.7.20.jar, /Users/weichou/.gradle/caches/transforms-3/394f4ba5a7303202a56e467034c8c851/transformed/core-ktx-1.9.0-api.jar, /Users/weichou/.gradle/caches/transforms-3/6e9fde4cc1497ecd85ffdf980a60e5ab/transformed/appcompat-resources-1.5.0-api.jar, /Users/weichou/.gradle/caches/transforms-3/aa25fefefb4284675752d1b8716a8bf4/transformed/drawerlayout-1.1.1-api.jar, /Users/weichou/.gradle/caches/transforms-3/6f5d72cc112503558a75fc45f0fbfe22/transformed/coordinatorlayout-1.1.0-api.jar, /Users/weichou/.gradle/caches/transforms-3/f66c58055f09c54df80d3ac39cfc8ed7/transformed/dynamicanimation-1.0.0-api.jar, /Users/weichou/.gradle/caches/transforms-3/76fb2fc3976e6e2590393baaa768ea01/transformed/recyclerview-1.1.0-api.jar, /Users/weichou/.gradle/caches/transforms-3/d5505f9d57d8c6a72c4bfbea8e0d1d59/transformed/transition-1.4.1-api.jar, /Users/weichou/.gradle/caches/transforms-3/cb376832b44347e0e2863a0efbfb46c1/transformed/vectordrawable-animated-1.1.0-api.jar, /Users/weichou/.gradle/caches/transforms-3/51c160350e1db060225cf076555bddc5/transformed/vectordrawable-1.1.0-api.jar, /Users/weichou/.gradle/caches/transforms-3/b437c042a4fb25b5473beb3aa3810946/transformed/viewpager-1.0.0-api.jar, /Users/weichou/.gradle/caches/transforms-3/c711a0ee0692c4fae81cdd671dadbde0/transformed/slidingpanelayout-1.2.0-api.jar, /Users/weichou/.gradle/caches/transforms-3/60367c0dc3b93ae22d44974ed438a5f5/transformed/customview-1.1.0-api.jar, /Users/weichou/.gradle/caches/transforms-3/05f2d7edda9b38183e0acbcdee061d41/transformed/legacy-support-core-utils-1.0.0-api.jar, /Users/weichou/.gradle/caches/transforms-3/5bb317b860d652a7e95da35400298e99/transformed/loader-1.0.0-api.jar, /Users/weichou/.gradle/caches/transforms-3/0c3f5120a16030c3b02e209277f606e0/transformed/core-1.9.0-api.jar, /Users/weichou/.gradle/caches/transforms-3/c9dedd1f96993d0760d55df081a29879/transformed/cursoradapter-1.0.0-api.jar, /Users/weichou/.gradle/caches/transforms-3/8bb096c8444b515fe520f2a8e48caab1/transformed/savedstate-ktx-1.2.0-api.jar, /Users/weichou/.gradle/caches/transforms-3/cdb42f31a8e9bad7a8ea34dbb5e7b7f6/transformed/savedstate-1.2.0-api.jar, /Users/weichou/.gradle/caches/transforms-3/9c4ff74ae88f4d922542d59f88faa6bc/transformed/cardview-1.0.0-api.jar, /Users/weichou/.gradle/caches/transforms-3/ee8da9fe5cd86ed12e7a623b8098a166/transformed/lifecycle-runtime-2.5.0-api.jar, /Users/weichou/.gradle/caches/transforms-3/b81c0a66f40e81ce8db2df92a1963d5b/transformed/versionedparcelable-1.1.1-api.jar, /Users/weichou/.gradle/caches/transforms-3/83b5d525a0ebd9bc00322953f05c96f4/transformed/lifecycle-viewmodel-2.5.0-api.jar, /Users/weichou/.gradle/caches/modules-2/files-2.1/androidx.collection/collection-ktx/1.1.0/f807b2f366f7b75142a67d2f3c10031065b5168/collection-ktx-1.1.0.jar, /Users/weichou/.gradle/caches/modules-2/files-2.1/androidx.collection/collection/1.1.0/1f27220b47669781457de0d600849a5de0e89909/collection-1.1.0.jar, /Users/weichou/.gradle/caches/transforms-3/f306f739dc2177bf68711c65fe4e11e2/transformed/lifecycle-livedata-2.0.0-api.jar, /Users/weichou/.gradle/caches/transforms-3/dfab4e028be7115b8ccc3796fb2e0428/transformed/core-runtime-2.1.0-api.jar, /Users/weichou/.gradle/caches/modules-2/files-2.1/androidx.arch.core/core-common/2.1.0/b3152fc64428c9354344bd89848ecddc09b6f07e/core-common-2.1.0.jar, /Users/weichou/.gradle/caches/transforms-3/82360dce7c90d10221f06f4ddfa5bc67/transformed/lifecycle-livedata-core-ktx-2.5.0-api.jar, /Users/weichou/.gradle/caches/transforms-3/8d444fd9988175cecd13055f04813b91/transformed/lifecycle-livedata-core-2.5.0-api.jar, /Users/weichou/.gradle/caches/modules-2/files-2.1/androidx.lifecycle/lifecycle-common/2.5.0/1fdb7349701e9cf2f0a69fc10642b6fef6bb3e12/lifecycle-common-2.5.0.jar, /Users/weichou/.gradle/caches/transforms-3/afa292a87f73b3eaac7c83ceefd92574/transformed/interpolator-1.0.0-api.jar, /Users/weichou/.gradle/caches/transforms-3/a7341bbf557f0eee7488093003db0f01/transformed/documentfile-1.0.0-api.jar, /Users/weichou/.gradle/caches/transforms-3/46ecd33fc8b0aff76d637a8fc3226518/transformed/localbroadcastmanager-1.0.0-api.jar, /Users/weichou/.gradle/caches/transforms-3/2e9cbd427ac36e8b9e40572d70105d5c/transformed/print-1.0.0-api.jar, /Users/weichou/.gradle/caches/modules-2/files-2.1/androidx.annotation/annotation/1.3.0/21f49f5f9b85fc49de712539f79123119740595/annotation-1.3.0.jar, /Users/weichou/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib-jdk7/1.7.20/2a729aa8763306368e665e2b747abd1dfd29b9d5/kotlin-stdlib-jdk7-1.7.20.jar, /Users/weichou/.gradle/caches/transforms-3/f45fdb3a6f32f3117a02913a5475531b/transformed/annotation-experimental-1.3.0-api.jar, /Users/weichou/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/1.7.20/726594ea9ba2beb2ee113647fefa9a10f9fabe52/kotlin-stdlib-1.7.20.jar, /Users/weichou/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib-common/1.7.20/e15351bdaf9fa06f009be5da7a202e4184f00ae3/kotlin-stdlib-common-1.7.20.jar, /Users/weichou/.gradle/caches/modules-2/files-2.1/org.jetbrains/annotations/13.0/919f0dfe192fb4e063e7dacadee7f8bb9a2672a9/annotations-13.0.jar, /Users/weichou/.gradle/caches/transforms-3/b61a19ce0ffb6b0eab4a6ede97932e2f/transformed/constraintlayout-2.1.4-api.jar, /Users/weichou/.gradle/caches/transforms-3/632e82547c61849d331ad6e31a1fb88c/transformed/annoid-af2b53cfce-api.jar, /Users/weichou/.gradle/caches/modules-2/files-2.1/com.github.dedge-space/scala-lang/253dc64cf9/51c97f073e45e5183af054e4596869d035f47b2d/scala-lang-253dc64cf9.jar, /Users/weichou/.gradle/caches/modules-2/files-2.1/org.scala-lang/scala-compiler/2.11.12/a1b5e58fd80cb1edc1413e904a346bfdb3a88333/scala-compiler-2.11.12.jar, /Users/weichou/.gradle/caches/modules-2/files-2.1/org.scala-lang/scala-reflect/2.11.12/2bb23c13c527566d9828107ca4108be2a2c06f01/scala-reflect-2.11.12.jar, /Users/weichou/.gradle/caches/modules-2/files-2.1/org.scala-lang.modules/scala-xml_2.11/1.0.5/77ac9be4033768cf03cc04fbd1fc5e5711de2459/scala-xml_2.11-1.0.5.jar, /Users/weichou/.gradle/caches/modules-2/files-2.1/org.scala-lang.modules/scala-parser-combinators_2.11/1.0.4/7369d653bcfa95d321994660477a4d7e81d7f490/scala-parser-combinators_2.11-1.0.4.jar, /Users/weichou/.gradle/caches/modules-2/files-2.1/org.scala-lang/scala-library/2.12.17/4a4dee1ebb59ed1dbce014223c7c42612e4cddde/scala-library-2.12.17.jar, /Users/weichou/.gradle/caches/modules-2/files-2.1/com.github.dedge-space/annoguard/v1.0.5-beta/d9f31382b1d2d4bbf8e34de4b7ef6a547277cfdb/annoguard-v1.0.5-beta.jar, /Users/weichou/.gradle/caches/modules-2/files-2.1/com.google.code.gson/gson/2.8.0/c4ba5371a29ac9b2ad6129b1d39ea38750043eff/gson-2.8.0.jar, /Users/weichou/git/bdo.cash/demo-material-3/app/build/tmp/kotlin-classes/githubDebug]
        //LOG.info "$NAME_PLUGIN ---> [wireScalaTasks]variant.getCompileClasspath(classpathKey):${compileClasspath.files}"

        // Configuration 'githubDebugCompileClasspath' was resolved during configuration time. 已经挪到上边了。
        /*scalaTask.configure { scala -> // ...
            scala.classpath += project.files(variant.getCompileClasspath(classpathKey).files.find { it.path.endsWith("${variant.name}/R.jar") })
        }*/
//        scalaTask.configure { scala -> // ...
//            scala.classpath += compileClasspath
//        }
        // 该方法没有接口，无法调用。
        //final prevRegistered = variant.getGeneratedBytecode()
        // 根据源码分析，只有没注册过的，才需进行注册（不过这里已经是构建的最后了，没有后续编译任务依赖这个 compileClasspath，用不上了）。
//        final clzPathKey = variant.registerPreJavacGeneratedBytecode(scalaOuts)
//        kotlinTask.configure { kt -> // `kt.classpath`用`kt.libraries`替代了。
        //final files = kt.libraries as ConfigurableFileCollection
        //files.from(variant.getGeneratedBytecode() - prevRegistered) //variant.getCompileClasspath(clzPathKey) - files)
//            kt.libraries.from(scalaOuts) // 根据上文和源码，直接这样用即可。

        /*if (!kt.incremental) {
            LOG.info "$NAME_PLUGIN ---> [wireScalaTasks]variant:${variant.name}, kt.incremental:${kt.incremental}, change to true."
            //kt.incremental = true // 用不到下面的，该值也就不用设置了。
        }*/
        // 报错：property 'classpathSnapshotProperties.useClasspathSnapshot' cannot be changed any further.
        // 根据源码，发现`kt.classpathSnapshotProperties`的任何值都是不能改变的：`task.classpathSnapshotProperties.classpathSnapshot.from(xxx).disallowChanges()`。
        // TODO: 要改变它的值，需要在 gradle.properties 中增加一行：
        //  `kotlin.incremental.useClasspathSnapshot=true`
        //  实测通过，写在 local.properties 也可以。
        //  不过，实测不适用于 scala-kotlin 交叉编译。https://blog.jetbrains.com/zh-hans/kotlin/2022/07/a-new-approach-to-incremental-compilation-in-kotlin/
        //kt.classpathSnapshotProperties.classpathSnapshot.from(scalaOuts)
        /*if (!kt.classpathSnapshotProperties.useClasspathSnapshot.get()) {
            LOG.info "$NAME_PLUGIN ---> [wireScalaTasks]variant:${variant.name}, useClasspathSnapshot:${kt.classpathSnapshotProperties.useClasspathSnapshot.get()}, change to true is disallow."
            //kt.classpathSnapshotProperties.useClasspathSnapshot.set(true)
        }*/
//        }

        // TODO: 综上，简写如下：
        LOG.info "$NAME_PLUGIN ---> [wireScalaTasks]scalaCodeReferToKt:${scalroid.scalaCodeReferToKt.get()}, ktCodeReferToScala:${scalroid.ktCodeReferToScala.get()}"
        checkArgsProbablyWarning(scalroid)

        final classpathKey = variant.registerPreJavacGeneratedBytecode(scalaOuts)
        if (scalroid.scalaCodeReferToKt.get()) {
            scalaTask.configure { scala -> // ...
                scala.classpath += variant.getCompileClasspath(classpathKey)
            }
            // 抑制警告：- Gradle detected a problem with the following location: '/Users/weichou/git/bdo.cash/demo-material-3/app/build/tmp/scala-classes/githubDebug'.
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
                    LOG.info "$NAME_PLUGIN ---> [configureScaladoc] >>>"
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

    private String genJavaCompileTaskName(String srcSetNameMatchVariant) {
        return "compile${srcSetNameMatchVariant.capitalize()}JavaWithJavac"
    }

    private String genKotlinCompileTaskName(String srcSetNameMatchVariant) {
        return "compile${srcSetNameMatchVariant.capitalize()}Kotlin"
    }

    private String genScalaCompileTaskName(String srcSetNameMatchVariant) {
        return "compile${srcSetNameMatchVariant.capitalize()}Scala"
    }

    private String genMergeJavaResourceTaskName(String srcSetNameMatchVariant) {
        return "merge${srcSetNameMatchVariant.capitalize()}JavaResource"
    }

    private String genBuildConfigTaskName(String srcSetNameMatchVariant) {
        return "generate${srcSetNameMatchVariant.capitalize()}BuildConfig"
    }

    private String genProcessResourcesTaskName(String srcSetNameMatchVariant) {
        return "process${srcSetNameMatchVariant.capitalize()}Resources"
    }

    private String genRFileTaskName(String srcSetNameMatchVariant) {
        return "generate${srcSetNameMatchVariant.capitalize()}RFile"
    }

    private String genDataBindingGenBaseTaskName(String srcSetNameMatchVariant) {
        return "dataBindingGenBaseClasses${srcSetNameMatchVariant.capitalize()}"
    }

    private String parseMainVariantNameForTest(String name) {
        //assert maybeIsTest(name)
        if (name == androidTest || name == test) {
            return main
        } else if (name.endsWith(androidTest.capitalize())) {
            return name.substring(0, name.length() - androidTest.length())
        } else {
            assert name.endsWith(test.capitalize())
            return name.substring(0, name.length() - test.length())
        }
    }

    private String parseAndroidTestForTest(String name) {
        if (name == androidTest || name == test) {
            return name
        } else if (!(name.contains(test) || name.contains(test.capitalize()))) {
            return null
        } else if (name.endsWith(androidTest.capitalize())) {
            return androidTest
        } else {
            assert name.endsWith(test.capitalize())
            return test
        }
    }

    private boolean maybeIsTest(String name) {
        final isTest = name == androidTest || name == test || name.endsWith(androidTest.capitalize()) || name.endsWith(test.capitalize())
        assert isTest || !(name.contains(androidTest) || name.contains(androidTest.capitalize()) || name.contains(test) || name.contains(test.capitalize()))
        return isTest
    }

    private String adjustSourceSetNameToMatchVariant(String sourceSetName, Set<String> variantsNames) {
        String srcSetNameMatchVariant = sourceSetName
        if (srcSetNameMatchVariant != main) {
            if (!variantsNames || !variantsNames.contains(srcSetNameMatchVariant)) {
                if (srcSetNameMatchVariant.startsWith(androidTest)) {
                    if (srcSetNameMatchVariant != androidTest) srcSetNameMatchVariant = srcSetNameMatchVariant.substring(androidTest.length()).uncapitalize() + androidTest.capitalize()
                } else if (srcSetNameMatchVariant.startsWith(test)) {
                    if (srcSetNameMatchVariant != test) srcSetNameMatchVariant = srcSetNameMatchVariant.substring(test.length()).uncapitalize() + test.capitalize()
                } else if (srcSetNameMatchVariant.contains(androidTest.capitalize()) || srcSetNameMatchVariant.contains(test.capitalize()) || srcSetNameMatchVariant.contains(androidTest) || srcSetNameMatchVariant.contains(test)) {
                    // 不在开头，那就在中间或结尾，即：`androidTest`或`test`的首字母大写。
                    //LOG.info "$NAME_PLUGIN ---> exception:${srcSetNameMatchVariant}"
                    throw new ProjectConfigurationException("sourceSet.name(${sourceSet.name}) not contains in `variants.names` and not within the expected range, please check.", new Throwable())
                }
            }
            if (variantsNames && !variantsNames.contains(srcSetNameMatchVariant)) {
                LOG.info "$NAME_PLUGIN ||| return (srcSetNameMatchVariant:$srcSetNameMatchVariant)"
                return null
            }
        }
        LOG.info "$NAME_PLUGIN ---> srcSetNameMatchVariant:${srcSetNameMatchVariant}"
        return srcSetNameMatchVariant
    }

    private void printConfiguration(Project project, configName) {
        //project.configurations.all { Configuration config ->
        //    LOG.info "$NAME_PLUGIN ---> configurations.name: ${config.name} -------- √√√"
        //    config.getDependencies().each { Dependency dep -> //
        //        LOG.info "    configurations.dependencies: ${dep.group}:${dep.name}:${dep.version}"
        //    }
        //    LOG.info ''
        //}
        project.configurations.named(configName).configure { Configuration config ->
            LOG.warn "$NAME_PLUGIN ---> configurations.name:${config.name} -------- √"
            config.dependencies.each { Dependency dep -> //
                LOG.warn "    `${configName} ${dep.group}:${dep.name}:${dep.version}`"
                LOG.warn "     ${' ' * configName.length()} > ${dep} / ${dep.class}"
            }
            LOG.warn ''
        }
    }

    private void printClasspathOnTaskDoFirst(Project project, Task task, Configuration classpathConfig, @Nullable String src$vaName, @Nullable String projNameOnly) {
        task.doFirst {
            try {
                if (!projNameOnly || project.name == projNameOnly) {
                    LOG.warn "$NAME_PLUGIN ---> [printClasspath]project:${project.name}, variant:${src$vaName}"
                    LOG.warn "$NAME_PLUGIN ---> [printClasspath]project.parent:${project.parent.name}, project.root:${project.rootProject.name}, project.depth:${project.depth}"
                    classpathConfig/*.incoming.artifactView { view ->
                        view.attributes { attr -> attr.attribute(artifactType, CLASSES_JAR) }
                    }.files.files*/
                    //.outgoing.artifacts
                            .each {
                                LOG.warn "${it.path}${it.path.endsWith('.aar') ? ' ×' : ''}"
                            }
                    LOG.warn ''
                }
            } catch (e) {
                LOG.warn "$NAME_PLUGIN ---> [printClasspath]$e"
            }
        }
    }
    /*private List<Project> findMainSubProject(Project project) {
        // 无法拿到其它 subproject 的配置信息，时序问题：apply `com.android.application`的 project 还未开始配置。
        LOG.info "$NAME_PLUGIN ---> [findMainSubProject]${project.rootProject} | name:${project.rootProject.name}, path:${project.rootProject.path}, version:${project.rootProject.version}"
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
