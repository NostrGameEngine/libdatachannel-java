cmake_minimum_required(VERSION 3.20)
project(libdatachannel_jni C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 11)

option(PROJECT_VERSION "The version of the project" "unspecified")
option(ENABLE_HARDENING "Enable compiler/linker hardening flags" ON)
option(BUILD_JNI_TESTS "Build the private JVM thread attachment test probe" OFF)
option(ALLOCATOR_DIAGNOSTICS "Instrument the private allocator for CI only" OFF)

set(OPENSSL_USE_STATIC_LIBS ON CACHE BOOL "Embed private OpenSSL in the binding" FORCE)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(LIBDATACHANNEL_JNI_IOS OFF)
if(CMAKE_SYSTEM_NAME STREQUAL "iOS")
    set(LIBDATACHANNEL_JNI_IOS ON)
    set(BUILD_SHARED_LIBS OFF CACHE BOOL "Build static libraries for iOS" FORCE)
    set(OPENSSL_USE_STATIC_LIBS ON CACHE BOOL "Use static OpenSSL for iOS" FORCE)
    # Materialize tentative C definitions so the relocatable link can localize
    # their storage instead of leaving private-external commons for the app link.
    add_compile_options($<$<COMPILE_LANGUAGE:C>:-fno-common>)
endif()

set(LIBDATACHANNEL_PRIVATE_ALLOCATOR OFF)
if(NOT ANDROID AND NOT LIBDATACHANNEL_JNI_IOS)
    set(LIBDATACHANNEL_PRIVATE_ALLOCATOR ON)
    include(FetchContent)
    FetchContent_Declare(
        mimalloc
        URL https://github.com/microsoft/mimalloc/archive/refs/tags/v2.5.2.tar.gz
        URL_HASH SHA256=5cf9d2a10fae5551b764e888a623da411c6c6beb0221b847f83492ab1932bdab
        DOWNLOAD_EXTRACT_TIMESTAMP FALSE
    )
    set(MI_SECURE ON CACHE BOOL "Enable mimalloc security features" FORCE)
    set(MI_USE_CXX ON CACHE STRING "Preserve C++ bad_alloc semantics" FORCE)
    set(MI_OVERRIDE OFF CACHE BOOL "Do not override global malloc symbols" FORCE)
    set(MI_OSX_INTERPOSE OFF CACHE BOOL "Do not interpose the process allocator" FORCE)
    set(MI_OSX_ZONE OFF CACHE BOOL "Do not replace the process malloc zone" FORCE)
    set(MI_WIN_REDIRECT OFF CACHE BOOL "Disable mimalloc Windows redirect exports" FORCE)
    if(MINGW)
        # The default CRT/TLS path initializes twice with MinGW's MSVCRT.
        # Use mimalloc's supported FLS lifecycle, without a second DLL entry point.
        set(MI_WIN_USE_FLS ON CACHE BOOL "Use one MinGW allocator initialization path" FORCE)
    endif()
    if(CMAKE_SYSTEM_NAME STREQUAL "Linux")
        # JVMs load JNI after startup; initial-exec TLS can exhaust the loader's reserve.
        set(MI_LOCAL_DYNAMIC_TLS ON CACHE BOOL "Use dlopen-compatible allocator TLS" FORCE)
    endif()
    set(MI_BUILD_SHARED OFF CACHE BOOL "Embed mimalloc in JNI" FORCE)
    set(MI_BUILD_STATIC ON CACHE BOOL "Build private static mimalloc" FORCE)
    set(MI_BUILD_OBJECT OFF CACHE BOOL "Do not build unused mimalloc objects" FORCE)
    set(MI_BUILD_TESTS OFF CACHE BOOL "Build mimalloc tests" FORCE)
    FetchContent_MakeAvailable(mimalloc)
    if(MINGW)
        get_target_property(private_allocator_definitions mimalloc-static COMPILE_DEFINITIONS)
        if(NOT "MI_WIN_INIT_USE_FLS=1" IN_LIST private_allocator_definitions)
            message(FATAL_ERROR "MinGW mimalloc must enable its FLS lifecycle")
        endif()
    endif()
    if(APPLE)
        include(cmake/private_cxx_runtime.cmake)
    endif()
endif()

if(ALLOCATOR_DIAGNOSTICS AND (NOT LIBDATACHANNEL_PRIVATE_ALLOCATOR OR NOT BUILD_JNI_TESTS))
    message(FATAL_ERROR "Allocator diagnostics require a desktop JNI test build")
endif()

set(NO_WEBSOCKET ON CACHE BOOL "configure libdatachannel build")
set(NO_MEDIA ON CACHE BOOL "configure libdatachannel build")
set(NO_TESTS ON CACHE BOOL "configure libdatachannel build")
set(NO_EXAMPLES ON CACHE BOOL "configure libdatachannel build")
if(LIBDATACHANNEL_PRIVATE_ALLOCATOR)
    find_package(OpenSSL REQUIRED)
endif()
add_subdirectory(libdatachannel)
set_property(TARGET datachannel PROPERTY EXCLUDE_FROM_ALL TRUE)

function(use_private_allocator target)
    target_compile_definitions(${target} PRIVATE LIBDATACHANNEL_PRIVATE_ALLOCATOR=1)
    target_include_directories(${target} PRIVATE "${mimalloc_SOURCE_DIR}/include")
    # C source redirection also covers DLL-imported CRT calls on Windows.
    target_compile_options(${target} PRIVATE
        "$<$<COMPILE_LANGUAGE:C>:-include${CMAKE_CURRENT_SOURCE_DIR}/src/mimalloc_redirect.h>")
    if(APPLE)
        add_dependencies(${target} private-cxx-runtime)
        target_compile_options(${target} PRIVATE "$<$<COMPILE_LANGUAGE:CXX>:-nostdinc++>")
        target_include_directories(${target} SYSTEM BEFORE PRIVATE "${PRIVATE_CXX_ROOT}/include/c++/v1")
    endif()
endfunction()

if(LIBDATACHANNEL_PRIVATE_ALLOCATOR)
    foreach(native_target IN ITEMS datachannel datachannel-static juice-static usrsctp mimalloc-static)
        if(TARGET ${native_target})
            use_private_allocator(${native_target})
        endif()
    endforeach()
endif()

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
    set(CMAKE_INSTALL_RPATH "")
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
        src/thread_attachment.cpp
        src/util.c
        src/native_channel.c
        src/native_peer.c
        src/native_track.c
        src/callback.c)
target_link_libraries(datachannel-java PRIVATE datachannel-static)
set_target_properties(datachannel-java PROPERTIES SKIP_BUILD_RPATH TRUE INSTALL_RPATH "")
if(NOT LIBDATACHANNEL_JNI_IOS)
    # Static dependencies must not expose or interpose implementation symbols.
    # Only JNI entry points belong to this shared library's public ABI.
    if(APPLE)
        find_program(LIBDATACHANNEL_OTOOL NAMES otool llvm-otool REQUIRED)
        target_link_options(datachannel-java PRIVATE
            "LINKER:-exported_symbols_list,${CMAKE_CURRENT_SOURCE_DIR}/cmake/jni.exports.apple")
        set_property(TARGET datachannel-java APPEND PROPERTY LINK_DEPENDS
            "${CMAKE_CURRENT_SOURCE_DIR}/cmake/jni.exports.apple")
    elseif(WIN32)
        if(MINGW)
            target_link_options(datachannel-java PRIVATE
                "LINKER:--exclude-all-symbols" "LINKER:--exclude-libs,ALL")
        endif()
    else()
        target_link_options(datachannel-java PRIVATE
            "LINKER:--version-script=${CMAKE_CURRENT_SOURCE_DIR}/cmake/jni.exports.map"
            "LINKER:--exclude-libs,ALL" "LINKER:--gc-sections" "LINKER:--no-undefined")
        set_property(TARGET datachannel-java APPEND PROPERTY LINK_DEPENDS
            "${CMAKE_CURRENT_SOURCE_DIR}/cmake/jni.exports.map")
    endif()
    add_custom_command(TARGET datachannel-java POST_BUILD
        COMMAND "${CMAKE_COMMAND}" -DINPUT_FILE=$<TARGET_FILE:datachannel-java>
            -DNM_TOOL=${CMAKE_NM} -DOBJDUMP_TOOL=${CMAKE_OBJDUMP}
            -DOTOOL_TOOL=${LIBDATACHANNEL_OTOOL} -DPLATFORM=${CMAKE_SYSTEM_NAME}
            -P "${CMAKE_CURRENT_SOURCE_DIR}/cmake/verify_private_dependencies.cmake"
        VERBATIM)
endif()
if(WIN32)
    target_sources(datachannel-java PRIVATE src/windows_entry.c)
endif()

if(BUILD_JNI_TESTS)
    add_library(datachannel-java-dependency-collision SHARED
        tests/dependency_collision.c tests/dependency_cpp_collision.cpp)
    if(UNIX AND NOT APPLE)
        target_link_options(datachannel-java-dependency-collision PRIVATE "LINKER:-Bsymbolic")
    endif()
    set_target_properties(datachannel-java-dependency-collision PROPERTIES PREFIX "lib")
    set_property(TARGET datachannel-java-dependency-collision PROPERTY CXX_STANDARD 17)
    add_library(datachannel-java-test-probe SHARED tests/thread_attachment_probe.cpp)
    set_target_properties(datachannel-java-test-probe PROPERTIES PREFIX "lib")
    target_include_directories(datachannel-java-test-probe PRIVATE jdk)
    if(WIN32)
        target_include_directories(datachannel-java-test-probe PRIVATE jdk/windows)
    else()
        target_include_directories(datachannel-java-test-probe PRIVATE jdk/unix)
        target_link_libraries(datachannel-java-test-probe PRIVATE ${CMAKE_DL_LIBS})
    endif()
    find_package(Threads REQUIRED)
    target_link_libraries(datachannel-java-test-probe PRIVATE Threads::Threads)
endif()
if(LIBDATACHANNEL_PRIVATE_ALLOCATOR)
    use_private_allocator(datachannel-java)
    target_sources(datachannel-java PRIVATE src/private_allocator.cpp)
    target_sources(datachannel-java PRIVATE src/private_malloc.c)
    set_source_files_properties(src/private_malloc.c PROPERTIES
        COMPILE_DEFINITIONS LIBDATACHANNEL_ALLOCATOR_IMPLEMENTATION=1)
    set_property(TARGET datachannel-java PROPERTY CXX_STANDARD 17)
    target_link_libraries(datachannel-java PRIVATE mimalloc-static OpenSSL::Crypto)
    if(ALLOCATOR_DIAGNOSTICS)
        # The adapter uses mimalloc's documented allocation/free tracking macros.
        # No upstream source is changed and no process allocator is interposed.
        target_compile_options(mimalloc-static PRIVATE
            "-include${CMAKE_CURRENT_SOURCE_DIR}/tests/allocator_diagnostics_hooks.h")
        target_sources(datachannel-java PRIVATE tests/allocator_diagnostics.c)
    endif()
    if(APPLE)
        target_link_options(datachannel-java PRIVATE -nostdlib++)
        target_link_libraries(datachannel-java PRIVATE
            "${PRIVATE_CXX_ROOT}/lib/libc++.a" "${PRIVATE_CXX_ROOT}/lib/libc++abi.a")
    else()
        target_link_options(datachannel-java PRIVATE -static-libstdc++ -static-libgcc)
    endif()
    if(BUILD_JNI_TESTS)
        foreach(component IN ITEMS datachannel-java juice-static usrsctp)
            string(REPLACE "-" "_" probe_name "${component}_allocator_probe")
            target_sources(${component} PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/tests/c_allocator_probe.c")
            target_compile_definitions(${component} PRIVATE "LIBDATACHANNEL_ALLOCATOR_PROBE=${probe_name}")
        endforeach()
        target_sources(mimalloc-static PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/tests/secure_allocator_probe.c")
        target_sources(datachannel-java PRIVATE tests/native_allocator_probe.cpp)
    endif()
endif()

if(LIBDATACHANNEL_JNI_IOS)
    set(LIBDATACHANNEL_JAVA_IOS_ARCHIVE "${CMAKE_CURRENT_BINARY_DIR}/LibDataChannelJava")
    set(LIBDATACHANNEL_JAVA_IOS_ARCHIVES
        "$<TARGET_FILE:datachannel-java>"
        "$<TARGET_FILE:datachannel-static>"
    )
    foreach(candidate_target IN ITEMS usrsctp juice-static)
        if(TARGET ${candidate_target})
            list(APPEND LIBDATACHANNEL_JAVA_IOS_ARCHIVES "$<TARGET_FILE:${candidate_target}>")
        endif()
    endforeach()
    add_custom_command(
        OUTPUT "${LIBDATACHANNEL_JAVA_IOS_ARCHIVE}"
        COMMAND xcrun libtool -static -o "${LIBDATACHANNEL_JAVA_IOS_ARCHIVE}" ${LIBDATACHANNEL_JAVA_IOS_ARCHIVES}
        DEPENDS datachannel-java datachannel-static
        VERBATIM
    )
    add_custom_target(datachannel-java-ios-archive ALL
        DEPENDS "${LIBDATACHANNEL_JAVA_IOS_ARCHIVE}"
    )
endif()
