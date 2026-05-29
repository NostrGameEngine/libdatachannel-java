plugins {
    id("tel.schich.libdatachannel.convention.common")
}

version = rootProject.version

val nativeLibs by configurations.registering

dependencies {
    api(rootProject)
    nativeLibs(project(mapOf(
        "path" to rootProject.path,
        "configuration" to Constants.IOS_CONFIG,
    )))
}

tasks.jar.configure {
    dependsOn(nativeLibs)
    for (jar in nativeLibs.get().resolvedConfiguration.resolvedArtifacts) {
        from(zipTree(jar.file)) {
            include("lib/ios/**")
            includeEmptyDirs = false
        }
    }
}

publishing.publications.withType<MavenPublication>().configureEach {
    pom {
        description = "${rootProject.description} The ${project.name} module bundles the iOS xcframework for libJGLIOS."
    }
}
