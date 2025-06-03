import org.gradle.api.tasks.bundling.Zip
import org.gradle.api.file.RelativePath

plugins {
    id("tel.schich.libdatachannel.convention.common")
}

version = rootProject.version

val nativeLibs by configurations.registering

dependencies {
    api(rootProject)
    nativeLibs(project(mapOf(
        "path" to rootProject.path,
        "configuration" to "archDetectConfiguration"
    )))
}
tasks.jar.configure {
    dependsOn(nativeLibs)
    val allArtifacts = mutableListOf<Pair<File, String>>()
    
    // Add resolved artifacts
    nativeLibs.get().resolvedConfiguration.resolvedArtifacts.forEach { artifact ->
        val classifier = artifact.classifier
        if (classifier != null && !classifier.startsWith("android-")) {
            allArtifacts.add(Pair(artifact.file, classifier))
        }
    }
    
    // Add prebuilt libraries
    val prebuiltArtifacts = rootProject.extra["prebuiltArtifacts"] as? List<Pair<File, String>> ?: emptyList()
    prebuiltArtifacts.forEach { (file, classifier) ->
        if (!classifier.startsWith("android-")) {
            allArtifacts.add(file to classifier)
            logger.lifecycle("Found prebuilt library: ${file.name} with classifier: $classifier")
        }
    }
    
    // Process all artifacts
    allArtifacts.forEach { (file, classifier) ->
        from(zipTree(file)) {
            include("native/*.so")
            include("native/*.dll")
            include("native/*.dylib")
            into(classifier)
        }
        logger.lifecycle("Added library to arch-detect JAR: ${file.name} with classifier: $classifier")
    }
}
// val androidAar by tasks.registering(Zip::class) {
//     group = "build"
//     archiveBaseName.set(project.name+"-android")
//     archiveExtension.set("aar")
//     destinationDirectory.set(layout.buildDirectory.dir("outputs/aar"))
//     val manifestFile = layout.buildDirectory.file("tmp/androidAar/AndroidManifest.xml")    
//     doFirst {
//         val mf = manifestFile.get().asFile
//         mf.parentFile.mkdirs()
//         mf.writeText(
//             """
//             <manifest xmlns:android="http://schemas.android.com/apk/res/android"
//                 package="${project.group}.libdatachannel.archdetect">
//             </manifest>
//             """.trimIndent()
//         )
//     }    
//     from(manifestFile) {
//         into("") 
//     }
//     dependsOn(nativeLibs, tasks.jar)
// }

// androidAar.configure {
//     nativeLibs.get().resolvedConfiguration.resolvedArtifacts.forEach { artifact ->
//         val classifier = artifact.classifier ?: return@forEach        
//         if (classifier.startsWith("android-")) {
//             var abi = classifier.removePrefix("android-")            
//             from(zipTree(artifact.file)) {
//                 include("native/*.so")
//                 includeEmptyDirs = false            
//                 eachFile {
//                     val p = path
//                     val newPath = RelativePath(true, "jni", abi, p.removePrefix("native/"))
//                     relativePath = newPath
//                 }
//             }
//         }
//     }
// }

// tasks.jar {
//     finalizedBy(androidAar)
// }

publishing.publications.withType<MavenPublication>().configureEach {
    // artifact(androidAar) {
    //     classifier = "android"
    // }
    pom {
        description = "${rootProject.description} The ${project.name} module bundles all architectures and allows runtime architecture detection."
    }
}