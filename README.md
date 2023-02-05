# Scalroid

[![Join the chat at https://gitter.im/scalroid/community](https://badges.gitter.im/scalroid/community.svg)](https://gitter.im/scalroid/community?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge)

[**`Discord#scala-android`**](https://discord.gg/RrtXCpsUZ5)

A `scala-kotlin-java` union compile `Gradle plugin`, for native Android.

* Also refers demo project [scalroid-build-demo](https://github.com/chenakam/scalroid-build-demo)

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
```

2. Add `apply plugin: 'cash.bdo.scalroid'` to your `app/build.gradle` file:

* See https://plugins.gradle.org/plugin/cash.bdo.scalroid if _**Step 1**_ was not done.

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
        javaDirsExcludes = ['/main/kotlin/']
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

3. You can edit any code in `your_project/buildSrc/src` as needed, and then click the **button** with tooltip `Sync Project with Gradle Files` in the toolbar of
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
