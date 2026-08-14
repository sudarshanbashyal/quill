// Quill's study logic — SM-2 scheduling, the review session, and quiz generation — as a plain JVM
// library rather than part of :app.
//
// `java-library`, not `com.android.library`, and that is the whole point of the module: the study
// logic has always been Android-free by convention, and a module that cannot see the Android
// classpath is the only version of that promise a compiler can enforce. An accidental
// `import android.content.Context` in here stops being a code-review catch and starts being a
// build failure.
//
// The immediate reason it exists is Epic J: the Wear companion reuses SM-2 verbatim instead of
// reimplementing it, so the schedule cannot drift between the wrist and the phone. The tests move
// with it and still run without a device.
plugins {
    `java-library`
}

java {
    // Matched to :app's compileOptions. A mismatch here would compile fine and then fail at dex
    // time with an unhelpful message about class file versions.
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

dependencies {
    testImplementation(libs.junit)
}
