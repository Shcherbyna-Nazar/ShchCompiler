plugins {
    kotlin("jvm") version "1.9.0"
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.bytedeco:llvm-platform:19.1.3-1.5.11")
    implementation("org.antlr:antlr4:4.13.1")
    implementation("org.antlr:antlr4-runtime:4.13.1")
    implementation(kotlin("stdlib"))
}

val antlrOutputDir = "build/generated-src/antlr/main"

val generateGrammarSource by tasks.registering(JavaExec::class) {
    group = "build"
    description = "Generuje parser ANTLR"
    inputs.file("src/main/antlr/Shch.g4")
    outputs.dir(antlrOutputDir)
    classpath = configurations.detachedConfiguration(
        dependencies.create("org.antlr:antlr4:4.13.1")
    )
    mainClass.set("org.antlr.v4.Tool")
    args = listOf(
        "-visitor",
        "-package", "shch",
        "-o", antlrOutputDir,
        "src/main/antlr/Shch.g4"
    )
}

sourceSets["main"].java.srcDirs("build/generated-src/antlr/main", "src/main/kotlin")


tasks.named("compileKotlin") {
    dependsOn(generateGrammarSource)
}

application {
    mainClass.set("MainKt")
}
