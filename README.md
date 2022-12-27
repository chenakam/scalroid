# Scalroid

A `scala-kotlin-java` union compile `Gradle plugin`, for native Android.

## Usage

1. Clone this repository to your project's `buildSrc` directory:

```bash
cd <PATH/TO/YOUR_PROJECT>
git clone git@github.com:chenakam/scalroid.git buildSrc
```

* Below is your project's directory structure as possibly looks like:

```text
/your_project
    /app
        /src
        /build.gradle
        /...
    /buildSrc
    /other_modules
    /...
```

2. Add `apply plugin: 'cash.bdo.scalroid'` to your `app/build.gradle` file:

```groovy
plugins {
    id 'com.android.application'
    id 'org.jetbrains.kotlin.android'
    // this plugin
    id 'cash.bdo.scalroid'
}

android {
    // optional settings
    //scalroid.scala.zincVersion = '1.3.5'
    scalroid {
        scala.zincVersion = '1.3.5'
        message = 'Hi'
        greeter = 'Gradle'
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
    implementation "org.scala-lang:scala-library:2.11.12"
    // Scala 3
//    implementation 'org.scala-lang:scala3-library_3:3.2.0-RC2'
}
```

3. You can edit any code in `your_project/buildSrc/src` as needed, and then click the **button** with tooltip `Sync Project with Gradle Files` in the toolbar of
   your `Android Studio`, your modified code will be applied immediately.

## Source Code Analysis

1. In your `app/build.gradle` file, add dependencies as following:

```groovy
repositories {
    // ...
    maven { url 'https://repo.gradle.org/gradle/libs-releases-local' }
}

dependencies {
    ////////// ////////// ////////// ////////// 便于看 android gradle 插件的源码 ////////// ////////// ////////// //////////
    // 注意：implementation 的 version 字段要与 id 'xxx' 的 version 保持一致（这在项目根目录下的`build.gradle`）。
    // id 'com.android.application' version '7.3.1' apply false
    // id 'org.jetbrains.kotlin.android' version '1.7.20' apply false

    // 这行不需要，External Libraries 下第一个就是`gradle-api-xxx.jar`。
    //implementation gradleApi()

    // 为了能够从 android gradle 插件的源码中链接到这些依赖。不过只更新到 6.1.1 了，原因不详。
    // https://repo.gradle.org/gradle/libs-releases-local/org/gradle/gradle-core-api/6.1.1/
    compileOnly 'org.gradle:gradle-core-api:6.1.1'

    // https://mvnrepository.com/artifact/com.android.tools.build/gradle
    implementation 'com.android.tools.build:gradle:7.3.1'
    implementation 'org.jetbrains.kotlin.android:org.jetbrains.kotlin.android.gradle.plugin:1.7.20'
    implementation 'org.jetbrains.kotlin:kotlin-build-common:1.7.20'
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
