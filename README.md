# libdatachannel-java (NGE)
[![License: MPL 2.0](https://img.shields.io/badge/License-MPL_2.0-blue.svg)](https://www.mozilla.org/en-US/MPL/2.0/)

This is a fork of [libdatachannel](https://github.com/paullouisageneau/libdatachannel) C Java wrappers: [libdatachannel-java](https://github.com/pschichtel/libdatachannel-java/tree/v0.24.1.1) intended to be used within the Nostr Game Engine codebase.

The fork diverges from the original repository in few key areas:

- Direct ByteBuffer allocation can be wired to a custom allocator
- Mimalloc (w/ MI_SECURE) for internal allocations on the jni binding side.
- JUL instead of SLF4J for logging.


## Usage

### Windows, MacOS, Linux
```kotlin
implementation("org.ngengine:libdatachannel-java:0.24.1.nge8")
implementation("org.ngengine:libdatachannel-java-arch-detect:0.24.1.nge8")
```

### Android
```kotlin
implementation("org.ngengine:libdatachannel-java:0.24.1.nge8")
implementation("org.ngengine:libdatachannel-java-android:0.24.1.nge8")
```  

To use libdatachannel on Android, the following permissions are required:

* `android.permission.INTERNET`

### iOS with libJGLIOS
```kotlin
implementation("org.ngengine:libdatachannel-java:0.24.1.nge8")
implementation("org.ngengine:libdatachannel-java-ios:0.24.1.nge8")
```

The iOS module bundles `LibDataChannelJava.xcframework`,
`LibSSL.xcframework`, and `LibCrypto.xcframework` under `lib/ios`, so
libJGLIOS can link the correct device or simulator slices from the runtime
classpath.
