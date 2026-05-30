cmake_minimum_required(VERSION 3.13)
project(libdatachannel_jni C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 11)

option(PROJECT_VERSION "The version of the project" "unspecified")
option(ENABLE_HARDENING "Enable compiler/linker hardening flags" ON)

set(LIBDATACHANNEL_JNI_IOS OFF)
if(CMAKE_SYSTEM_NAME STREQUAL "iOS")
    set(LIBDATACHANNEL_JNI_IOS ON)
    set(BUILD_SHARED_LIBS OFF CACHE BOOL "Build static libraries for iOS" FORCE)
    set(OPENSSL_USE_STATIC_LIBS ON CACHE BOOL "Use static OpenSSL for iOS" FORCE)
endif()

# Add mimalloc with MI_SECURE for non-Android targets
if(NOT ANDROID)
    include(FetchContent)
    FetchContent_Declare(
        mimalloc
        URL https://github.com/microsoft/mimalloc/archive/refs/tags/v2.1.7.tar.gz
        DOWNLOAD_EXTRACT_TIMESTAMP FALSE
    )
    set(MI_SECURE ON CACHE BOOL "Enable mimalloc security features" FORCE)
    set(MI_OVERRIDE OFF CACHE BOOL "Do not override global malloc symbols" FORCE)
    set(MI_WIN_REDIRECT OFF CACHE BOOL "Disable mimalloc Windows redirect exports" FORCE)
    if(LIBDATACHANNEL_JNI_IOS)
        set(MI_BUILD_SHARED OFF CACHE BOOL "Do not build shared mimalloc for iOS" FORCE)
        set(MI_BUILD_STATIC ON CACHE BOOL "Build static mimalloc for iOS" FORCE)
    else()
        set(MI_BUILD_SHARED ON CACHE BOOL "Build mimalloc as shared library" FORCE)
    endif()
    set(MI_BUILD_TESTS OFF CACHE BOOL "Build mimalloc tests" FORCE)
    FetchContent_MakeAvailable(mimalloc)
    set(MIMALLOC_BUILD_TARGET mimalloc)
    if(LIBDATACHANNEL_JNI_IOS)
        set(MIMALLOC_BUILD_TARGET mimalloc-static)
    endif()
    set(MIMALLOC_PREFIX_HEADER "${CMAKE_CURRENT_SOURCE_DIR}/src/mimalloc_prefix_symbols.h")
    if(MSVC)
        target_compile_options(${MIMALLOC_BUILD_TARGET} PRIVATE /FI"${MIMALLOC_PREFIX_HEADER}")
    else()
        target_compile_options(${MIMALLOC_BUILD_TARGET} PRIVATE -include "${MIMALLOC_PREFIX_HEADER}")
    endif()
    if(WIN32)
        set_target_properties(${MIMALLOC_BUILD_TARGET} PROPERTIES OUTPUT_NAME "libdatachannel_mimalloc")
    else()
        set_target_properties(${MIMALLOC_BUILD_TARGET} PROPERTIES OUTPUT_NAME "datachannel_mimalloc")
    endif()
    set_target_properties(${MIMALLOC_BUILD_TARGET} PROPERTIES VERSION "" SOVERSION "")
    if(WIN32)
        set(MIMALLOC_RUNTIME_NAME "libdatachannel_mimalloc.dll")
    elseif(APPLE)
        set(MIMALLOC_RUNTIME_NAME "libdatachannel_mimalloc.dylib")
    else()
        set(MIMALLOC_RUNTIME_NAME "libdatachannel_mimalloc.so")
    endif()
endif()

set(NO_WEBSOCKET ON CACHE BOOL "configure libdatachannel build")
set(NO_MEDIA ON CACHE BOOL "configure libdatachannel build")
set(NO_TESTS ON CACHE BOOL "configure libdatachannel build")
set(NO_EXAMPLES ON CACHE BOOL "configure libdatachannel build")
add_subdirectory(libdatachannel)

include_directories(libdatachannel/include generated)
include_directories(jdk)
if(WIN32)
    include_directories(jdk/windows)
else()
    include_directories(jdk/unix)
endif()


add_compile_options(-Werror -Wall -Wextra -Wno-unused-parameter -fPIC -D "PROJECT_VERSION=${PROJECT_VERSION}")
if(CMAKE_BUILD_TYPE STREQUAL "Release")
    add_compile_options(-O3)
    if(NOT LIBDATACHANNEL_JNI_IOS)
        # enable LTO, if supported:
        include(CheckIPOSupported)
        check_ipo_supported(RESULT ipo_supported OUTPUT ipo_error)
        if(ipo_supported)
            set(CMAKE_INTERPROCEDURAL_OPTIMIZATION_RELEASE TRUE)
        else()
            message(WARNING "IPO/LTO is not supported: ${ipo_error}")
        endif()
    endif()
else()
    add_compile_options(-g3 -Og)
endif()

if(ENABLE_HARDENING)
    if(CMAKE_C_COMPILER_ID MATCHES "GNU|Clang")
        add_compile_options(-fstack-protector-strong)
        if(NOT APPLE)
            if(NOT ANDROID)
                if(MINGW)
                    add_compile_definitions(_FORTIFY_SOURCE=2)
                else()
                    add_compile_definitions(_FORTIFY_SOURCE=3)
                endif()
            endif()
            include(CheckCCompilerFlag)
            check_c_compiler_flag("-fcf-protection=full" COMPILER_SUPPORTS_CF_PROTECTION)
            if(COMPILER_SUPPORTS_CF_PROTECTION)
                add_compile_options(-fcf-protection=full)
            endif()
        endif()
    endif()
endif()

if(APPLE)
    add_link_options(-dead_strip)
    set(CMAKE_POSITION_INDEPENDENT_CODE ON)
    set(CMAKE_INSTALL_RPATH "@loader_path")
    set(CMAKE_BUILD_WITH_INSTALL_RPATH TRUE)
    set(CMAKE_MACOSX_RPATH ON)
elseif(NOT WIN32)
    add_link_options(-z noexecstack)
    if(ENABLE_HARDENING)
        add_link_options(-Wl,-z,relro -Wl,-z,now)
    endif()
else()
    add_link_options(-static)
endif()

set(LIBDATACHANNEL_JAVA_LIBRARY_TYPE SHARED)
if(LIBDATACHANNEL_JNI_IOS)
    set(LIBDATACHANNEL_JAVA_LIBRARY_TYPE STATIC)
endif()

add_library(datachannel-java ${LIBDATACHANNEL_JAVA_LIBRARY_TYPE}
        generated/jni-c-to-java.c
        src/init.c
        src/util.c
        src/native_channel.c
        src/native_peer.c
        src/native_track.c
        src/callback.c)
target_link_libraries(datachannel-java PRIVATE datachannel-static)
if(NOT ANDROID)
    set(LIBDATACHANNEL_MIMALLOC_TARGET mimalloc)
    if(LIBDATACHANNEL_JNI_IOS)
        set(LIBDATACHANNEL_MIMALLOC_TARGET mimalloc-static)
    endif()
    target_link_libraries(datachannel-java PRIVATE ${LIBDATACHANNEL_MIMALLOC_TARGET})
    if(MSVC)
        target_compile_options(datachannel-java PRIVATE /FI"${CMAKE_CURRENT_SOURCE_DIR}/src/mimalloc_redirect.h")
    else()
        target_compile_options(datachannel-java PRIVATE -include "${CMAKE_CURRENT_SOURCE_DIR}/src/mimalloc_redirect.h")
    endif()
    if(APPLE)
        set_target_properties(datachannel-java PROPERTIES INSTALL_RPATH "@loader_path")
    elseif(NOT WIN32)
        set_target_properties(datachannel-java PROPERTIES INSTALL_RPATH "$ORIGIN")
    endif()
    if(NOT LIBDATACHANNEL_JNI_IOS)
        add_custom_command(
            TARGET datachannel-java POST_BUILD
            COMMAND "${CMAKE_COMMAND}" -E copy_if_different
                "$<TARGET_FILE:mimalloc>"
                "$<TARGET_FILE_DIR:datachannel-java>/${MIMALLOC_RUNTIME_NAME}"
            VERBATIM
        )
        add_custom_command(
            TARGET datachannel-java POST_BUILD
            COMMAND "${CMAKE_COMMAND}"
                -DINPUT_FILE=$<TARGET_FILE:mimalloc>
                -DNM_TOOL=${CMAKE_NM}
                -DOBJDUMP_TOOL=${CMAKE_OBJDUMP}
                -P "${CMAKE_CURRENT_SOURCE_DIR}/cmake/verify_mimalloc_symbols.cmake"
            VERBATIM
        )
    endif()
endif()

if(LIBDATACHANNEL_JNI_IOS)
    set(LIBDATACHANNEL_JAVA_IOS_ARCHIVE "${CMAKE_CURRENT_BINARY_DIR}/LibDataChannelJava")
    set(LIBDATACHANNEL_JAVA_IOS_ARCHIVES
        "$<TARGET_FILE:datachannel-java>"
        "$<TARGET_FILE:datachannel-static>"
        "$<TARGET_FILE:${LIBDATACHANNEL_MIMALLOC_TARGET}>"
    )
    foreach(candidate_target IN ITEMS usrsctp juice-static)
        if(TARGET ${candidate_target})
            list(APPEND LIBDATACHANNEL_JAVA_IOS_ARCHIVES "$<TARGET_FILE:${candidate_target}>")
        endif()
    endforeach()
    add_custom_command(
        OUTPUT "${LIBDATACHANNEL_JAVA_IOS_ARCHIVE}"
        COMMAND xcrun libtool -static -o "${LIBDATACHANNEL_JAVA_IOS_ARCHIVE}" ${LIBDATACHANNEL_JAVA_IOS_ARCHIVES}
        DEPENDS datachannel-java datachannel-static ${LIBDATACHANNEL_MIMALLOC_TARGET}
        VERBATIM
    )
    add_custom_target(datachannel-java-ios-archive ALL
        DEPENDS "${LIBDATACHANNEL_JAVA_IOS_ARCHIVE}"
    )
endif()
