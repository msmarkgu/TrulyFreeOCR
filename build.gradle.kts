import org.gradle.api.tasks.testing.TestDescriptor
import org.gradle.api.tasks.testing.TestListener
import org.gradle.api.tasks.testing.TestResult
import java.time.Duration

plugins {
    application
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "com.trulyfreeocr"
version = "1.0.0"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.apache.pdfbox:pdfbox:3.0.6")
    implementation("com.github.jai-imageio:jai-imageio-jpeg2000:1.4.0")
    implementation("info.picocli:picocli:4.7.6")
    annotationProcessor("info.picocli:picocli-codegen:4.7.6")

    implementation("com.google.code.gson:gson:2.10.1")

    implementation("com.microsoft.onnxruntime:onnxruntime:1.26.0")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("com.trulyfreeocr.TrulyFreeOCR")
}

tasks.named<Test>("test") {
    dependsOn("generateTestPdfs")
    doFirst {
        // Ensure Tesseract TSV config file exists for OCR tests
        val configsDir = file("deps/tesseract/tessdata/configs")
        configsDir.mkdirs()
        val tsvConfig = File(configsDir, "tsv")
        if (!tsvConfig.exists()) {
            tsvConfig.writeText("tessedit_create_tsv 1\n")
        }
    }
    outputs.upToDateWhen { false }
    maxParallelForks = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    minHeapSize = "512m"
    maxHeapSize = "2g"
    timeout.set(Duration.ofMinutes(10))
    useJUnitPlatform {
        excludeTags("eval")
    }
    testLogging {
        events("passed", "skipped", "failed")
    }
    addTestListener(object : TestListener {
        override fun afterSuite(desc: TestDescriptor, result: TestResult) {
            if (desc.parent == null) {
                val sb = StringBuilder()
                sb.append("Tests: ${result.testCount} total")
                sb.append(", ${result.successfulTestCount} passed")
                if (result.failedTestCount > 0) sb.append(", ${result.failedTestCount} FAILED")
                if (result.skippedTestCount > 0) sb.append(", ${result.skippedTestCount} skipped")
                println(sb.toString())
            }
        }
        override fun beforeSuite(desc: TestDescriptor) {}
        override fun beforeTest(desc: TestDescriptor) {}
        override fun afterTest(desc: TestDescriptor, result: TestResult) {}
    })
    environment("TESSDATA_PREFIX", "${project.projectDir}/deps/tesseract/tessdata")
}

tasks.register<Test>("testEval") {
    outputs.upToDateWhen { false }
    useJUnitPlatform {
        includeTags("eval")
    }
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
    maxHeapSize = "2g"
    environment("TESSDATA_PREFIX", "${project.projectDir}/deps/tesseract/tessdata")
}

tasks.register<JavaExec>("generateCorpus") {
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.trulyfreeocr.eval.CorpusGenerator")
    args("--force")
}

tasks.register<JavaExec>("generateTestPdfs") {
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.trulyfreeocr.TestPdfGenerator")
    args("--force")
}

tasks {
    shadowJar {
        archiveBaseName.set("trulyfreeocr")
        archiveClassifier.set("")
        archiveVersion.set("")
        destinationDirectory.set(layout.buildDirectory)
    }
    build {
        dependsOn(shadowJar)
        dependsOn.removeAll { it.toString().endsWith("check") }
    }
}
