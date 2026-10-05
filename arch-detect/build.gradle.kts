import org.gradle.kotlin.dsl.support.serviceOf

plugins {
    id("tel.schich.libdatachannel.convention.common")
}

version = rootProject.version

val nativeLibs by configurations.registering

dependencies {
    api(rootProject)
    nativeLibs(project(mapOf(
        "path" to rootProject.path,
        "configuration" to Constants.ARCH_DETECT_CONFIG,
    )))
}

tasks.jar.configure {
    val nativeChecker = rootProject.layout.projectDirectory.file("tools/verify_macos_native.py")
    inputs.file(nativeChecker)
    doLast {
        val execOps = project.serviceOf<ExecOperations>()
        for (architecture in listOf("arm64", "x86_64")) {
            execOps.exec {
                commandLine("python3", nativeChecker.asFile, architecture, archiveFile.get().asFile)
            }
        }
    }
    dependsOn(nativeLibs)
    for (jar in nativeLibs.get().resolvedConfiguration.resolvedArtifacts) {
        val classifier = jar.classifier ?: continue
        from(zipTree(jar.file)) {
            include("native/*.so")
            include("native/*.dll")
            include("native/*.dylib")
            into(classifier)
        }
    }
}

publishing.publications.withType<MavenPublication>().configureEach {
    pom {
        description = "${rootProject.description} The ${project.name} module bundles all architectures and allows runtime architecture detection."
    }
}