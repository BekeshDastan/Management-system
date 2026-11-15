plugins { id("org.jetbrains.kotlin.jvm") }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    sourceSets {
        main { kotlin.srcDir(rootProject.file("app/src/main/java/app/diet/domain")); kotlin.srcDir(rootProject.file("app/src/main/java/app/diet/timing")) }
        test { kotlin.srcDir(rootProject.file("app/src/test/java/app/diet/domain")); kotlin.srcDir(rootProject.file("app/src/test/java/app/diet/timing")) }
    }
}
sourceSets.test { resources.srcDir(rootProject.file("app/src/test/resources")) }
dependencies { testImplementation(libs.junit) }
tasks.test { useJUnit(); maxHeapSize = "512m" }
