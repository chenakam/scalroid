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
