plugins {
    kotlin("jvm") version "2.3.0"
    application
}

group = "com.androidstudioexperience"
version = "0.1.0"

repositories {
    mavenCentral()
    maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies/")
    google()
}

dependencies {
    // LSP4J — note the correct groupId (org.eclipse.lsp4j:org.eclipse.lsp4j)
    implementation("org.eclipse.lsp4j:org.eclipse.lsp4j:0.24.0")

    implementation(kotlin("stdlib"))
    implementation("org.slf4j:slf4j-simple:2.0.13")

    // RocksDB — persistent symbol index
    implementation("org.rocksdb:rocksdbjni:9.7.3")

    // ASM — bytecode reading for external library (.jar/.class) indexing
    implementation("org.ow2.asm:asm:9.8")

    // Vineflower — decompile .class from libraries for go-to-definition
    implementation("org.vineflower:vineflower:1.11.1")

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.androidstudioexperience.lsp.MainKt")
}

tasks.withType<Jar> {
    manifest {
        attributes["Main-Class"] = "com.androidstudioexperience.lsp.MainKt"
    }
}

// Fat jar for easy distribution
tasks.register<Jar>("fatJar") {
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes["Main-Class"] = "com.androidstudioexperience.lsp.MainKt"
    }
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get().filter { it.name.endsWith("jar") }.map { zipTree(it) }
    }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/**/module-info.class")
    }
}