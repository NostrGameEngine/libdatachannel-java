#!/usr/bin/env bash

set -euo pipefail

set -x

export CONAN_HOME="${OUTPUT_DIR}/conan/home"
# OpenSSL's makefile constructs broken compiler paths due to CROSS_COMPILE
export CROSS_COMPILE=""

write_ios_info_plist() {
  local plist_file=$1
  local framework_name=$2
  local min_ios_version=$3
  mkdir -p "$(dirname "$plist_file")"
  cat > "$plist_file" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleDevelopmentRegion</key>
    <string>en</string>
    <key>CFBundleExecutable</key>
    <string>${framework_name}</string>
    <key>CFBundleIdentifier</key>
    <string>tel.schich.libdatachannel.${framework_name}</string>
    <key>CFBundleInfoDictionaryVersion</key>
    <string>6.0</string>
    <key>CFBundleName</key>
    <string>${framework_name}</string>
    <key>CFBundlePackageType</key>
    <string>FMWK</string>
    <key>CFBundleShortVersionString</key>
    <string>${PROJECT_VERSION}</string>
    <key>CFBundleVersion</key>
    <string>${PROJECT_VERSION}</string>
    <key>MinimumOSVersion</key>
    <string>${min_ios_version}</string>
</dict>
</plist>
EOF
}

write_conan_build_profile() {
  local profile=$1
  local host_arch
  host_arch="$(uname -m)"
  case "$host_arch" in
    arm64|aarch64) host_arch='armv8' ;;
    x86_64|amd64) host_arch='x86_64' ;;
  esac
  mkdir -p "$(dirname "$profile")"
  cat > "$profile" <<EOF
[settings]
os=Macos
arch=${host_arch}
compiler=apple-clang
compiler.version=${CONAN_APPLE_CLANG_VERSION:-17}
compiler.libcxx=libc++
compiler.cppstd=17
build_type=${PROJECT_BUILD_TYPE}
EOF
}

write_conan_ios_profile() {
  local profile=$1
  local sdk=$2
  local sdk_path=$3
  local min_ios_version=$4
  mkdir -p "$(dirname "$profile")"
  cat > "$profile" <<EOF
[settings]
os=iOS
os.version=${min_ios_version}
os.sdk=${sdk}
arch=armv8
compiler=apple-clang
compiler.version=${CONAN_APPLE_CLANG_VERSION:-17}
compiler.libcxx=libc++
compiler.cppstd=17
build_type=${PROJECT_BUILD_TYPE}

[conf]
tools.apple:sdk_path=${sdk_path}
tools.build:compiler_executables={"c":"/usr/bin/clang","cpp":"/usr/bin/clang++"}
EOF
}

build_ios_slice() {
  local sdk=$1
  local cmake_sdk=$2
  local target_triple=$3
  local slice_name=$4
  local min_ios_version=$5
  local framework_name='LibDataChannelJava'
  local sdk_path
  sdk_path="$(xcrun --sdk "$sdk" --show-sdk-path)"
  local slice_root="${OUTPUT_DIR}/ios/${slice_name}"
  local build_dir="${slice_root}/build"
  local conan_install_dir="${slice_root}/conan"
  local host_profile="${slice_root}/conan-${slice_name}.profile"
  local build_profile="${OUTPUT_DIR}/ios/conan-build.profile"

  write_conan_build_profile "$build_profile"
  write_conan_ios_profile "$host_profile" "$sdk" "$sdk_path" "$min_ios_version"
  conan install "${MOUNT_SOURCE}/jni" \
    -of="$conan_install_dir" \
    --profile:host="$host_profile" \
    --profile:build="$build_profile" \
    --build=missing \
    -s "build_type=${PROJECT_BUILD_TYPE}"

  cmake -S "$RELATIVE_PROJECT_PATH" -B "$build_dir" \
    -DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
    -DCMAKE_PREFIX_PATH="$conan_install_dir" \
    -DOpenSSL_DIR="$conan_install_dir" \
    -DZLIB_DIR="$conan_install_dir" \
    -DCMAKE_SYSTEM_NAME=iOS \
    -DCMAKE_OSX_SYSROOT="$cmake_sdk" \
    -DCMAKE_OSX_ARCHITECTURES=arm64 \
    -DCMAKE_OSX_DEPLOYMENT_TARGET="$min_ios_version" \
    -DCMAKE_C_COMPILER_TARGET="$target_triple" \
    -DCMAKE_CXX_COMPILER_TARGET="$target_triple" \
    -DCMAKE_FIND_PACKAGE_PREFER_CONFIG=ON \
    -DPROJECT_VERSION="$PROJECT_VERSION" \
    -DCMAKE_BUILD_TYPE="$PROJECT_BUILD_TYPE" \
    -DENABLE_HARDENING="${ENABLE_HARDENING:-ON}" \
    -DOPENSSL_USE_STATIC_LIBS=ON \
    -DBUILD_SHARED_LIBS=OFF
  cmake --build "$build_dir" --target datachannel-java-ios-archive --parallel "${JOBS:-1}"

  local framework_dir="${slice_root}/${framework_name}.framework"
  rm -rf "$framework_dir"
  mkdir -p "$framework_dir/Headers"
  cp -v "${build_dir}/${framework_name}" "${framework_dir}/${framework_name}"
  write_ios_info_plist "${framework_dir}/Info.plist" "$framework_name" "$min_ios_version"
}

if [ "$TARGET_FAMILY" = 'ios' ]
then
  min_ios_version="${IOS_MIN_VERSION:-15.0}"
  framework_name='LibDataChannelJava'
  xcframework_dir="${OUTPUT_DIR}/lib/ios/${framework_name}.xcframework"
  metadata_file="${OUTPUT_DIR}/lib/ios/${framework_name}.xcframework.json"

  build_ios_slice 'iphoneos' 'iphoneos' "arm64-apple-ios${min_ios_version}" 'ios-arm64' "$min_ios_version"
  build_ios_slice 'iphonesimulator' 'iphonesimulator' "arm64-apple-ios${min_ios_version}-simulator" 'ios-arm64-simulator' "$min_ios_version"

  rm -rf "$xcframework_dir"
  mkdir -p "$(dirname "$xcframework_dir")"
  xcodebuild -create-xcframework \
    -framework "${OUTPUT_DIR}/ios/ios-arm64/${framework_name}.framework" \
    -framework "${OUTPUT_DIR}/ios/ios-arm64-simulator/${framework_name}.framework" \
    -output "$xcframework_dir"
  cat > "$metadata_file" <<EOF
{
  "name": "${framework_name}",
  "embed": false,
  "forceLoad": true,
  "systemFrameworks": [
    "Security"
  ]
}
EOF
  exit 0
fi

predefined_profile_path='/conan-profile.ini'
classifier_profile_path="${MOUNT_SOURCE}/jni/conan-profiles/$TARGET_CLASSIFIER.ini"
profile_path="${CONAN_HOME}/profiles/default"
mkdir -vp "$(dirname "$profile_path")"
if [ -e "$predefined_profile_path" ]
then
  cp -av "$predefined_profile_path" "$profile_path"
elif [ -e "$classifier_profile_path" ]
then
  cp -av "$classifier_profile_path" "$profile_path"
else
  conan profile detect -f
fi

cmake_options=(
  '-DCMAKE_POLICY_VERSION_MINIMUM=3.5'
  "-DCMAKE_PROJECT_TOP_LEVEL_INCLUDES=${MOUNT_SOURCE}/jni/cmake-conan/conan_provider.cmake"
  "-DPROJECT_VERSION=${PROJECT_VERSION}"
  "-DCMAKE_BUILD_TYPE=${PROJECT_BUILD_TYPE}"
  "-DENABLE_HARDENING=${ENABLE_HARDENING:-ON}"
)

if [ "$TARGET_FAMILY" = 'android' ]
then
  cmake_options+=(
    "-DANDROID_ABI=${ANDROID_ABI}"
    "-DANDROID_PLATFORM=android-21"
    "-DANDROID_STL=c++_static"
    "-DANDROID_NDK=${ANDROID_NDK_ROOT}"
  )
elif [ "$TARGET_FAMILY" = 'macos' ]
then
  cp -v "${MOUNT_SOURCE}/jni/conan-profiles/${TARGET_CLASSIFIER}.ini" "$profile_path"
  cmake_options+=(
    "-DOSXCROSS_HOST=${OSXCROSS_HOST}"
  )
fi

cmake "$RELATIVE_PROJECT_PATH" "${cmake_options[@]}"
make -j"${JOBS:-1}"
