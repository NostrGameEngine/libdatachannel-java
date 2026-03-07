#include "util.h"
#include <errno.h>
#include <jni-c-to-java.h>
#include <jni-java-to-c.h>
#if !defined(__ANDROID__)
#include <mimalloc.h>
#endif
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

jstring get_dynamic_string(JNIEnv* env, const char* func_name, get_dynamic_string_func func, int handle) {
    int size = wrap_error(env, "", func(handle, NULL, -1));
    if (size == EXCEPTION_THROWN) {
        return NULL;
    }
    if (size <= 0) {
        throw_native_exception(env, "Invalid dynamic string size");
        return NULL;
    }
    char* memory = malloc(size);
    if (memory == NULL) {
        throw_native_exception(env, "Failed to allocate memory for string");
        return NULL;
    }
    if (wrap_error(env, func_name, func(handle, memory, size)) == EXCEPTION_THROWN) {
        free(memory);
        return NULL;
    }
    jstring result = (*env)->NewStringUTF(env, memory);
    free(memory);
    return result;
}

jint wrap_error(JNIEnv* env, const char* message, int result) {
    if (result > 0) {
        return result;
    }
    switch (result) {
        case RTC_ERR_SUCCESS:
            return RTC_ERR_SUCCESS;
        case RTC_ERR_INVALID:
            throw_tel_schich_libdatachannel_exception_InvalidException_cstr(env, message);
            return EXCEPTION_THROWN;
        case RTC_ERR_FAILURE:
            throw_tel_schich_libdatachannel_exception_FailureException_cstr(env, message);
            return EXCEPTION_THROWN;
        case RTC_ERR_NOT_AVAIL:
            throw_tel_schich_libdatachannel_exception_NotAvailableException_cstr(env, message);
            return EXCEPTION_THROWN;
        case RTC_ERR_TOO_SMALL:
            throw_tel_schich_libdatachannel_exception_TooSmallException_cstr(env, message);
            return EXCEPTION_THROWN;
        default:
            throw_tel_schich_libdatachannel_exception_UnknownFailureException_cstr(env, result, message);
            return EXCEPTION_THROWN;
    }
}

void throw_native_exception(JNIEnv* env, char* msg) {
    // It is necessary to get the errno before any Java or JNI function is called, as it
    // may become changed due to the VM operations.
    int errorNumber = errno;

    throw_tel_schich_libdatachannel_exception_NativeOperationException_cstr(env, msg, errorNumber, strerror(errorNumber));
}

JNIEXPORT void JNICALL Java_tel_schich_libdatachannel_LibDataChannel_freeMemory(JNIEnv* env, jclass clazz, jlong address) {
    void* ptr = (void*) (intptr_t) address;
    free(ptr);
}

JNIEXPORT jstring JNICALL Java_tel_schich_libdatachannel_LibDataChannel_getInnerAllocatorNative(JNIEnv* env, jclass clazz) {
    (void) clazz;
#if !defined(__ANDROID__)
    const int mimalloc_version = mi_version();
    const int major = mimalloc_version / 100;
    const int minor = (mimalloc_version / 10) % 10;
    const int patch = mimalloc_version % 10;
    char allocator_name[64];
    int written = snprintf(allocator_name, sizeof(allocator_name), "mimalloc+%d.%d.%d", major, minor, patch);
    if (written <= 0 || written >= (int) sizeof(allocator_name)) {
        return (*env)->NewStringUTF(env, "mimalloc");
    }
    return (*env)->NewStringUTF(env, allocator_name);
#else
    return (*env)->NewStringUTF(env, "passthrough");
#endif
}
