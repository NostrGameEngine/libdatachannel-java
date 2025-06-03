import tel.schich.dockcross.execute.DockerRunner
import tel.schich.dockcross.execute.NonContainerRunner
import tel.schich.dockcross.execute.SubstitutingString
import tel.schich.dockcross.tasks.DockcrossRunTask
import java.nio.file.Files

plugins {
    id("tel.schich.libdatachannel.convention.common")
    alias(libs.plugins.dockcross)
    alias(libs.plugins.nexusPublish)
}

fun extractLibDataChannelVersion(): String {
    val regex = """#define\s+RTC_VERSION\s+"([^"]+)"""".toRegex()
    val headerPath = project.layout.projectDirectory
        .file("jni/libdatachannel/include/rtc/version.h")
        .asFile.toPath()
    val headerContent = Files.readString(headerPath)
    val match = regex.find(headerContent) ?: return "unknown"

    return match.groupValues[1]
}

fun produceVersion(): String {
    var providedVersion = project.findProperty("version")?.toString()
    if (providedVersion != null && providedVersion.isNotEmpty() && providedVersion != "unspecified") {
        return providedVersion
    }

    val libDataChannelVersion = extractLibDataChannelVersion()
    val hasTags = project.providers.exec {
        commandLine("git", "tag")
    }.standardOutput.asText.get().trim().isNotEmpty()
    val defaultVersion = "$libDataChannelVersion.0-SNAPSHOT"
    if (!hasTags) {
        return defaultVersion
    }
    val describeOutput = project.providers.exec {
        commandLine("git", "describe", "--tags")
    }.standardOutput.asText.get().trim().removePrefix("v")

    val parts = describeOutput.split("-", limit = 2)
    val tagVersion = parts[0]
    return if (parts.size > 1) {
        if (tagVersion.startsWith(libDataChannelVersion)) {
            val versionParts = tagVersion.split('.').toMutableList()
            versionParts[versionParts.size - 1] = versionParts[versionParts.size - 1].toInt().inc().toString()
            versionParts.joinToString(".") + "-SNAPSHOT"
        } else {
            defaultVersion
        }
    } else {
        if (tagVersion.startsWith(libDataChannelVersion)) {
            tagVersion
        } else {
            throw GradleException("The version derived from the latest git tag is conflicting with libdatachannel!")
        }
    }
}

version = produceVersion()
description = "${project.name} is a binding to the libdatachannel that feels native to Java developers."

val currentVersion by tasks.registering(DefaultTask::class) {
    doLast {
        println(version)
    }
}

val archDetectConfiguration by configurations.registering {
    isCanBeConsumed = true
}

val jniPath = project.layout.projectDirectory.dir("jni")
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf("-Agenerate.jni.headers=true"))
    options.headerOutputDirectory = jniPath.dir("generated")
}

val nativeGroup = "native"
val ci = System.getenv("CI") != null
val buildReleaseBinaries = project.findProperty("libdatachannel.build-release-binaries")
    ?.toString()
    ?.ifEmpty { null }
    ?.toBooleanStrictOrNull()
    ?: !project.version.toString().endsWith("-SNAPSHOT")



// Detect prebuilt libraries
project.extra["prebuiltArtifacts"] = mutableListOf<Pair<File, String>>()
val prebuiltLibsDir = project.layout.projectDirectory.dir("prebuilt")
if (prebuiltLibsDir.asFile.exists()) {
    prebuiltLibsDir.asFile.listFiles()?.filter { 
        it.isFile && it.name.endsWith(".jar") 
    }?.forEach { jarFile ->
        // Extract version and classifier from filename
        val filenameRegex = """libdatachannel-java-(.*)-([^-]+)-([^-]+)\.jar""".toRegex();
        val matchResult = filenameRegex.find(jarFile.name)
        if (matchResult != null) {
            val (version, os, arch) = matchResult.destructured
            val classifier = "${os}-${arch}"
            
            if (version == project.version.toString()) {
                // Add to publications
                publishing.publications.withType<MavenPublication>().configureEach {
                    artifact(jarFile) {
                        this.classifier = classifier
                    }
                }
                
                // Add to the prebuilt artifacts list in project.extra
                val prebuiltArtifacts = project.extra["prebuiltArtifacts"] as MutableList<Pair<File, String>>
                prebuiltArtifacts.add(jarFile to classifier)
                
                logger.lifecycle("Added prebuilt library: ${jarFile.name} with classifier: $classifier")
            } else {
                logger.warn("Skipping prebuilt library ${jarFile.name} as its version $version does not match the project version ${project.version}.")
            }
        } else {
            logger.warn("Skipping prebuilt library ${jarFile.name} as it does not match the expected naming convention.")
        }
    }
}


fun DockcrossRunTask.baseConfigure(outputTo: Directory, target: BuildTarget) {
    group = nativeGroup

    image = target.image
    dockcrossTag = "20250109-7bf589c"
    inputs.dir(jniPath)

    dependsOn(tasks.compileJava)

    output = outputTo.dir("native")
    val conanDir = "conan"
    extraEnv.putAll(target.env)
    extraEnv.put("CONAN_HOME", SubstitutingString("\${OUTPUT_DIR}/$conanDir/home"))
    // OpenSSL's makefile constructs broken compiler paths due to CROSS_COMPILE
    extraEnv.put("CROSS_COMPILE", "")
 
    val relativePathToProject = output.get().asFile.toPath().relativize(jniPath.asFile.toPath()).toString()
    val projectVersionOption = "-DPROJECT_VERSION=${project.version}"
    val releaseOption = "-DCMAKE_BUILD_TYPE=${if (buildReleaseBinaries) "Release" else "Debug"}"
    val conanProviderOption = SubstitutingString("-DCMAKE_PROJECT_TOP_LEVEL_INCLUDES=\${MOUNT_SOURCE}/jni/cmake-conan/conan_provider.cmake")   
    val androidNdkOption = SubstitutingString("-DCMAKE_ANDROID_NDK=\${MOUNT_SOURCE}/ndk")
 
    script = buildList{         
        if (target.image.startsWith("android")) {
            add(listOf("sh", "-c", """
                if [ ! -d "/work/ndk" ]; then
                    echo "Downloading Android NDK..."
                    wget -q https://dl.google.com/android/repository/android-ndk-r26d-linux.zip -O /tmp/ndk.zip
                    unzip -q /tmp/ndk.zip -d /tmp/
                    ls /tmp/
                    mv /tmp/android-ndk-r26d /work/ndk
                    rm /tmp/ndk.zip
                fi
            """.trimIndent()))          
        }  
        add(listOf("conan", "profile", "detect", "-f"))
        add(listOf("cmake", relativePathToProject, conanProviderOption, projectVersionOption, releaseOption, androidNdkOption) + target.args)
        add(listOf("make", "-j${project.gradle.startParameter.maxWorkerCount}"))
    }

    if (ci) {
        runner(DockerRunner())
    }
}

fun Jar.baseConfigure(compileTask: TaskProvider<DockcrossRunTask>, buildOutputDir: Directory) {
    group = nativeGroup

    dependsOn(compileTask)

    from(buildOutputDir) {
            include("native/libdatachannel-java.so")
            include("native/libdatachannel-java.dll")
            include("native/libdatachannel-java.dylib")
        }
    }

val dockcrossOutputDir: Directory = project.layout.buildDirectory.get().dir("dockcross")
val nativeForHostOutputDir: Directory = dockcrossOutputDir.dir("host")
val compileNativeForHost by tasks.registering(DockcrossRunTask::class) {
    baseConfigure(nativeForHostOutputDir, BuildTarget(image = "host", classifier = "host"))
    unsafeWritableMountSource = true
    runner(NonContainerRunner)
}

val packageNativeForHost by tasks.registering(Jar::class) {
    baseConfigure(compileNativeForHost, nativeForHostOutputDir)
    archiveClassifier = "host"
}

data class BuildTarget(
    val image: String,
    val classifier: String,
    val env: Map<String, String> = emptyMap(),
    val args: List<String> = emptyList(),
)

val isMacOS = org.gradle.internal.os.OperatingSystem.current().isMacOsX()
val targets = buildList {
    // Always add these targets
    add(BuildTarget(image = "linux-x64", classifier = "x86_64"))
    add(BuildTarget(image = "linux-x86", classifier = "x86_32"))
    add(BuildTarget(image = "linux-arm64", classifier = "aarch64"))
    add(BuildTarget(image = "windows-static-x64", classifier = "windows-x86_64"))
    // BuildTarget(image = "windows-static-x86", classifier = "windows-x86_32"),
   
    // Add macOS targets only when running on macOS
    if (isMacOS) {
        add(BuildTarget(image = "host", classifier = "darwin-x86_64", 
                args = listOf("-DCMAKE_OSX_ARCHITECTURES=x86_64", "-DCMAKE_POLICY_VERSION_MINIMUM=3.5")))
        add(BuildTarget(image = "host", classifier = "darwin-arm64", 
                args = listOf("-DCMAKE_OSX_ARCHITECTURES=arm64", "-DCMAKE_POLICY_VERSION_MINIMUM=3.5")))
    }
    
    // Android targets
    // add(BuildTarget(
    //     image = "android-arm64", 
    //     classifier = "android-arm64-v8a",
    //     args = listOf("-DANDROID_ABI=arm64-v8a", "-DANDROID_PLATFORM=android-21"),
    // ))
    // add(BuildTarget(
    //     image = "android-x86_64", 
    //     classifier = "android-x86_64",
    //     args = listOf("-DANDROID_ABI=x86_64", "-DANDROID_PLATFORM=android-21"),
    // ))
    // BuildTarget(
    //     image = "android-arm", 
    //     classifier = "android-armeabi-v7a",
    //     args = listOf("-DANDROID_ABI=armeabi-v7a", "-DANDROID_PLATFORM=android-21"),
    // ),
}

val packageNativeAll by tasks.registering(DefaultTask::class) {
    group = nativeGroup
}


for (target in targets) {
    val outputDir: Directory = dockcrossOutputDir.dir(target.classifier)
    val taskSuffix = target.classifier.split("[_-]".toRegex())
        .joinToString(separator = "") { it.lowercase().replaceFirstChar(Char::uppercaseChar) }
    val compileNative = tasks.register("compileNativeFor$taskSuffix", DockcrossRunTask::class) {
        baseConfigure(outputDir, target)
        unsafeWritableMountSource = true
        containerName = "dockcross-${project.name}-${target.classifier}"


        if (target.image == "host" ){
            runner(NonContainerRunner)
        }else if (ci) {
            runner(DockerRunner())
        }
    }

    val packageNative = tasks.register("packageNativeFor$taskSuffix", Jar::class) {
        baseConfigure(compileNative, outputDir)
        archiveClassifier = target.classifier
    }

    publishing.publications.withType<MavenPublication>().configureEach {
        artifact(packageNative)
    }

    packageNativeAll.configure {
        dependsOn(packageNative)
    }

    artifacts.add(archDetectConfiguration.name, packageNative)
}

dependencies {
    annotationProcessor(libs.jniAccessGenerator)
    compileOnly(libs.jniAccessGenerator)

    testImplementation(files(packageNativeForHost))
}

publishing.publications.withType<MavenPublication>().configureEach {
    pom {
        description = "${project.description}"
    }
}
 
allprojects {
    apply(plugin = "maven-publish")
    publishing {
        repositories {
            maven {
                name = "GitHubPackages"
                url = uri("https://maven.pkg.github.com/${project.findProperty("gpr.owner") as? String ?: System.getenv("GITHUB_REPOSITORY_OWNER")}/libdatachannel-java")
                credentials {
                    username = project.findProperty("gpr.user") as? String ?: System.getenv("USERNAME")
                    password = project.findProperty("gpr.key") as? String ?: System.getenv("TOKEN")
                }
            }
            mavenLocal()
            maven {
                name = "distFolder"
                url = uri("file://${project.rootDir}/dist")
            }
        }
    }
}