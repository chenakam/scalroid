# Scalroid

[![Join the chat at https://gitter.im/scalroid/community](https://badges.gitter.im/scalroid/community.svg)](https://gitter.im/scalroid/community?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge)

[**`Discord#scala-android`**](https://discord.gg/RrtXCpsUZ5)

A `scala-kotlin-java` joint compilation plugin built on `Gradle`, for `native Android`.

The plugin was built with `ScalaBasePlugin`, which is also an official plugin of `Gradle`, cooperates perfectly with the `Android` official plugin, is a supplement. It
takes very little code to put the two together but functional maturation, includes `androidTest` and `unitTest`. There are no conflicts or incompatibilities, even if it
does, it's easy to update and maintain.

Now, this plugin is well developed and ready for official use.

* Also refers demo project [scalroid-build-demo](https://github.com/chenakam/scalroid-build-demo)

## Supported versions

| Gradle          | Android Plugin    | Kotlin Plugin            | Scala (this plugin compiles) |
|-----------------|-------------------|--------------------------|------------------------------|
| `7.5` and newer | `7.4.0` and newer | `1.7.20` ~ `1.8.20-Beta` | `2.10.x` ~ `3.x`             |

* The Scala version fully supports the `ScalaPlugin` of gradle, see official documentation:
  https://docs.gradle.org/current/userguide/scala_plugin.html#sec:configure_zinc_compiler  
  For details about how to set `zincVersion`, see the example code below.

* Known issues:  
  Since the Android's built-in _`JDK/JRE`_ does NOT have implements the class `java.lang.ClassValue`, but some classes require it, such as `scala.reflect.ClassTag`. So
  i have made a copy [_**here**_](https://github.com/bdo-cash/assoid/blob/v.gradle/src/main/scala/java/lang/ClassValue.java).  
  Or as an alternative, you can set _`cacheDisabled = true`_ in [**`ClassTag`**](https://github.com/scala/scala/blob/2.12.x/src/library/scala/reflect/ClassTag.scala#L140)
  to avoid method calls to **`ClassValue`**. To achieve this, you can
  use [_**`classTagDisableCache`**_](https://github.com/bdo-cash/assoid/blob/v.gradle/src/main/scala/scala/compat/classTagDisableCache.scala) (it works well even
  after `Proguard/R8`) at the very beginning of your app startup (
  e.g. [_**`AbsApp`**_](https://github.com/bdo-cash/assoid/blob/v.gradle/src/main/scala/hobby/wei/c/core/AbsApp.scala#L51)). But you still need to define a simple class
  so that it can be found at runtime:
  ```java
    package java.lang;
    //import hobby.wei.c.anno.proguard.Keep$;
    //@Keep$
    public abstract class ClassValue<T> {
        protected abstract T computeValue(Class<?> type);
        public T get(Class<?> type) { return null; }
        public void remove(Class<?> type) {}
    }
  ```

## Usage

1. Clone this repository to your project's `buildSrc` directory (**optional**):

```bash
cd <PATH/TO/YOUR_PROJECT>
git clone git@github.com:chenakam/scalroid.git buildSrc
```

* Below is your project's directory structure as possibly looks like:

```text
/your_project
    /app
        /src
            /main
                /java     # Your java/kotlin code dir
                /scala    # Your scala code dir (java files can also compile)
        /build.gradle
        /...
    /buildSrc             # This repo dir
    /other_modules
    /...
    /build.gradle
    /...
```

2. Add `id 'cash.bdo.scalroid' xxx` to your `/build.gradle` file:

* See also https://plugins.gradle.org/plugin/cash.bdo.scalroid

```groovy
// ...
plugins {
    id 'com.android.application' version '7.4.1' apply false
    id 'com.android.library' version '7.4.1' apply false
    id 'org.jetbrains.kotlin.android' version '1.8.0' apply false

    // TODO: if you have not clone the dir `buildSrc/`, you need to uncomment the `version` filed.
    id 'cash.bdo.scalroid' /*version '[1.3,)'*/ apply false
}
```

3. Add `apply plugin: 'cash.bdo.scalroid'` to your `app/build.gradle` file:

```groovy
plugins {
    id 'com.android.application'
    // Alternatively, for your library subproject
    //id 'com.android.library'

    id 'org.jetbrains.kotlin.android' // Required
    // or (abbr)
    //id 'kotlin-android'

    // This plugin
    id 'cash.bdo.scalroid'
}

android {
    scalroid {
//        scala.zincVersion.set('1.6.1')
//        scalaCodeReferToKt = false
//        ktCodeReferToScala = true
//        javaDirsExcludes = ['src/main/kotlin']
    }
    // ...
}

dependencies {
    implementation "androidx.core:core-ktx:1.8.0"
    implementation 'androidx.appcompat:appcompat:1.4.2'
    implementation 'androidx.constraintlayout:constraintlayout:2.1.4'
    // ...

    // `scala-library` must be set. Scala 2 or Scala 3

    // Scala 2
    implementation "org.scala-lang:scala-library:2.12.17"
    // Scala 3
//    implementation 'org.scala-lang:scala3-library_3:3.2.0-RC2'
}
```

4. You can edit any code in `your_project/buildSrc/src` as needed, and then click the **button** with tooltip `Sync Project with Gradle Files` in the toolbar of
   your `Android Studio`, your modified code will be applied immediately.

## Preferences

It's not that easy to get `scala` and `kotlin` code to compile together in one go. Especially when it comes to cross-referencing code. So, here are two options to help
you compile through.

#### _a. Leverage Java, is preferred_

You can use Java as a transition, scala and kotlin both refer to java instead of directly referencing each other. At most, you can also use kotlin code to refer directly
to scala (by default). That should do it.

Don't forget the following **settings** if you put java source files in a non-default folder, e.g.`src/aaa/xxx`. The default folders are `src/main/java`
and `src/main/kotlin` and do not need to be set.

```groovy
scalroid {
    javaDirsExcludes = ['src/main/xxx', 'src/aaa/xxx']
}
```

Note: `.java` (and `.scala`) files written in `src/main/scala` directory will be compiled and output by the scala compiler. `.java` files written in other directories
will **NOT** be compiled and output by scala even if they are referenced by `.scala` files (which would be compiled for output by the java compiler). But if NOT
configured in `javaDirsExcludes`, output is compiled by scala by default, which may results in duplicate `.class` files and an error is reported. If similar error occurs,
try adding the directories to `javaDirsExcludes`.

In other words, if you want scala to compile, you should written `.java` (and `.scala`) files in the `src/main/scala` directory (or other directories you configured
in `sourceSets`), otherwise you should written them in the `java/kotlin` directories.

#### _b. Cross-referencing is inevitable_

There are two parameters you can use conveniently, and they are:

```groovy
scalroid {
    scalaCodeReferToKt = false // `false` by default
    ktCodeReferToScala = true  // `true` by default
}
```

The default value means **the kotlin code references scala code, but it cannot be back referenced** because of *scala-kotlin cross compilation* has not implemented. If
this is the case, do nothing else, leave the defaults, and the project compiles. Or the opposite one-way reference works:

```groovy
scalroid {
    // One-way reference works
    scalaCodeReferToKt = false // `false` by default
    ktCodeReferToScala = true  // `true` by default

    // Alternatively:
    // The opposite one-way reference also works too
    scalaCodeReferToKt = true
    ktCodeReferToScala = false
}
```

But given the existence of such a situation of **scala/kotlin code tends to cross-reference**, I also made compatible reluctantly so that we can compile finally. It's
theoretically set up like this (but the truth is not so simple):

```groovy
scalroid {
    scalaCodeReferToKt = true
    ktCodeReferToScala = true
}
```

* If this is set, once you `clean` the project, you will got errors the next time you compile.

The correct steps are (tentatively and reluctantly):

1. `Clean` project (run `./gradlew clean`);
2. Comment out any code in `scala` that references `kotlin` (or vice versa) and uniformize the two parameters setting to maintain a one-way reference (no need to
   click `Sync`, which doesn't matter);
3. Try compiling until you pass (`./gradlew :app:compile{VARIANT}JavaWithJavac`), `kotlin-classes` and `scala-classes` in `app/build/tmp/` have output associated
   with `VARIANT`;
4. Sets the two parameters both to `true` and **uncomment** any code commented out in Step 2;
5. Try compiling until you pass.

* Note: repeat these steps each time you `clean` your project.

----

## Source Code Analysis

1. In your `app/build.gradle` file, add dependencies as following:

```groovy
repositories {
    // ...
    maven { url 'https://repo.gradle.org/gradle/libs-releases-local' }
}

dependencies {
    ////////// ////////// ////////// ////////// 便于看 android gradle 插件的源码 ////////// ////////// ////////// //////////
    // 注意：compileOnly 的 version 字段要与 id 'xxx' 的 version 保持一致（这在项目根目录下的`build.gradle`）。
    // id 'com.android.application' version '7.3.1' apply false
    // id 'org.jetbrains.kotlin.android' version '1.7.20' apply false

    // 这行不需要，External Libraries 下第一个就是`gradle-api-xxx.jar`。
    //implementation gradleApi()

    // 为了能够从 android gradle 插件的源码中链接到这些依赖。不过只更新到 6.1.1 了，原因不详。
    // https://repo.gradle.org/gradle/libs-releases-local/org/gradle/gradle-core-api/6.1.1/
    compileOnly 'org.gradle:gradle-core-api:6.1.1'

    // https://mvnrepository.com/artifact/com.android.tools.build/gradle
    compileOnly 'com.android.tools.build:gradle:7.3.1'
    compileOnly 'org.jetbrains.kotlin.android:org.jetbrains.kotlin.android.gradle.plugin:1.7.20'
    compileOnly 'org.jetbrains.kotlin:kotlin-build-common:1.7.20'
    ////////// ////////// ////////// ////////// ////////// ////////// ////////// ////////// ////////// ////////// //////////
}
```

2. Key Points

```java
// 关键源代码：
// org.jetbrains.kotlin.gradle.plugin.KotlinAndroidPlugin ->
//   .apply() ->
//   .dynamicallyApplyWhenAndroidPluginIsApplied() ->
//   [companion object]
//   androidTargetHandler().configureTarget(target)
//
// org.jetbrains.kotlin.gradle.plugin.AbstractAndroidProjectHandler ->
//   .configureTarget() ->
//   .postprocessVariant() ->
//   .wireKotlinTasks() ->
// org.jetbrains.kotlin.gradle.plugin.Android25ProjectHandler ->
//   .wireKotlinTasks() ->
//   project.files(xxx).builtBy(kotlinTask)
//
// kotlin 使用的 SourceSet：
// org.jetbrains.kotlin.gradle.model.SourceSet
// 不同于默认的 org.gradle.api.tasks.SourceSet
// 但 Android 项目的 kotlin 也不是上述之一，而是一个目录集：
// com.android.build.api.dsl.AndroidSourceSet ->
//   kotlin: com.android.build.api.dsl.AndroidSourceDirectorySet
// org.jetbrains.kotlin.gradle.plugin.mpp.SyncKotlinAndAndroidSourceSetsKt ->
//   internal fun AndroidSourceSet.addKotlinSources(kotlinSourceSet: KotlinSourceSet)
```
