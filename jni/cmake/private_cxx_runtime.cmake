# A shared system libc++ would allocate outside the private heap and can later
# release objects allocated by this binding. Keep the entire C++ runtime local.
include(FetchContent)
include(ExternalProject)
set(private_cxx_source "${CMAKE_BINARY_DIR}/private-cxx-source")
set(PRIVATE_CXX_ROOT "${CMAKE_BINARY_DIR}/private-cxx")
set(llvm_release "https://github.com/llvm/llvm-project/releases/download/llvmorg-20.1.8")
foreach(package IN ITEMS cmake runtimes libcxx libcxxabi)
    if(package STREQUAL "cmake")
        set(digest 3319203cfd1172bbac50f06fa68e318af84dcb5d65353310c0586354069d6634)
    elseif(package STREQUAL "runtimes")
        set(digest 86b375ab217619d018f6875b7c71106ffab5ab21c425f414a11468c2a8262819)
    elseif(package STREQUAL "libcxx")
        set(digest 3e12992e3d1a0e648dc2e1c8c6b1dd31717ac8e684a25a302773667f47f75546)
    else()
        set(digest b4f6d3f9c802ea9e5db260e3e13177f63e8f8feef48618a8d53d4f7d4fcbb07f)
    endif()
    FetchContent_Declare(private_${package}
        URL "${llvm_release}/${package}-20.1.8.src.tar.xz"
        URL_HASH "SHA256=${digest}"
        SOURCE_DIR "${private_cxx_source}/${package}"
        SOURCE_SUBDIR unused
        DOWNLOAD_EXTRACT_TIMESTAMP FALSE)
    FetchContent_MakeAvailable(private_${package})
endforeach()
# Only CMake modules and shared libc headers are needed from the full release.
FetchContent_Declare(private_llvm_cmake
    URL "${llvm_release}/llvm-project-20.1.8.src.tar.xz"
    URL_HASH SHA256=6898f963c8e938981e6c4a302e83ec5beb4630147c7311183cf61069af16333d
    DOWNLOAD_NO_EXTRACT TRUE
    SOURCE_SUBDIR unused)
FetchContent_MakeAvailable(private_llvm_cmake)
if(NOT EXISTS "${private_cxx_source}/libc/src/errno/libc_errno.h")
    file(ARCHIVE_EXTRACT
        INPUT "${private_llvm_cmake_SOURCE_DIR}/llvm-project-20.1.8.src.tar.xz"
        DESTINATION "${private_cxx_source}"
        PATTERNS "llvm-project-20.1.8.src/llvm/cmake/*" "llvm-project-20.1.8.src/libc/*")
    file(COPY "${private_cxx_source}/llvm-project-20.1.8.src/llvm"
        "${private_cxx_source}/llvm-project-20.1.8.src/libc" DESTINATION "${private_cxx_source}")
endif()
file(MAKE_DIRECTORY "${PRIVATE_CXX_ROOT}/include/c++/v1")
set(runtime_platform_args "")
foreach(setting IN ITEMS CMAKE_OSX_ARCHITECTURES CMAKE_OSX_SYSROOT CMAKE_OSX_DEPLOYMENT_TARGET)
    if(${setting})
        list(APPEND runtime_platform_args "-D${setting}=${${setting}}")
    endif()
endforeach()
ExternalProject_Add(private-cxx-runtime
    SOURCE_DIR "${private_cxx_source}/runtimes"
    BINARY_DIR "${CMAKE_BINARY_DIR}/private-cxx-build"
    DOWNLOAD_COMMAND ""
    LIST_SEPARATOR |
    CMAKE_ARGS
        "-DCMAKE_C_COMPILER=${CMAKE_C_COMPILER}"
        "-DCMAKE_CXX_COMPILER=${CMAKE_CXX_COMPILER}"
        "-DCMAKE_BUILD_TYPE=Release"
        "-DCMAKE_INSTALL_PREFIX=${PRIVATE_CXX_ROOT}"
        "-DCMAKE_POSITION_INDEPENDENT_CODE=ON"
        "-DLLVM_ENABLE_RUNTIMES=libcxx|libcxxabi"
        "-DLLVM_INCLUDE_TESTS=OFF"
        "-DLLVM_INCLUDE_DOCS=OFF"
        "-DLLVM_ENABLE_PER_TARGET_RUNTIME_DIR=OFF"
        "-DLIBCXX_INCLUDE_TESTS=OFF"
        "-DLIBCXXABI_INCLUDE_TESTS=OFF"
        "-DLIBCXX_ENABLE_SHARED=OFF"
        "-DLIBCXXABI_ENABLE_SHARED=OFF"
        "-DLIBCXX_ENABLE_NEW_DELETE_DEFINITIONS=OFF"
        "-DLIBCXXABI_ENABLE_NEW_DELETE_DEFINITIONS=OFF"
        "-DLIBCXXABI_USE_LLVM_UNWINDER=OFF"
        "-DLIBCXX_ENABLE_STATIC_ABI_LIBRARY=ON"
        "-DLIBCXX_HERMETIC_STATIC_LIBRARY=ON"
        "-DLIBCXXABI_HERMETIC_STATIC_LIBRARY=ON"
        ${runtime_platform_args}
    INSTALL_COMMAND "${CMAKE_COMMAND}" --build <BINARY_DIR> --target install-cxx install-cxxabi
    BUILD_BYPRODUCTS "${PRIVATE_CXX_ROOT}/lib/libc++.a" "${PRIVATE_CXX_ROOT}/lib/libc++abi.a")
