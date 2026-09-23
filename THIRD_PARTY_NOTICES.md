# Third-party notices

APM's Android application uses Kotlin and AndroidX/Jetpack Compose components declared
in `android/app/build.gradle.kts`. AndroidX components are distributed under the
[Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0); Kotlin is also
[Apache-2.0 licensed](https://github.com/JetBrains/kotlin/blob/master/license/LICENSE.txt).
The Gradle wrapper retains its upstream license header.

Neither the production APK nor the instrumentation APK distributes ONNX Runtime,
OpenCV, LiteRT, model weights, or third-party reference photographs. Tests use generated
images and vectors. Python runtime code uses only the standard library.

Earlier versions bundled model weights and public test images. Their provenance and
license records remain in version history and the dated validation documents; their
removal does not change the licenses of any previously distributed artifact.
