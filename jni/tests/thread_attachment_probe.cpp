#include <jni.h>
#include <atomic>
#include <cstring>
#include <cstdint>
#include <thread>
#include <vector>
#ifdef _WIN32
#include <windows.h>
#else
#include <dlfcn.h>
#endif

namespace {

using EnvironmentFunction = JNIEnv* (*)(JavaVM*);

EnvironmentFunction environment_function(JNIEnv* env, jstring library) {
    const char* path = env->GetStringUTFChars(library, nullptr);
    if (path == nullptr) return nullptr;
    EnvironmentFunction function = nullptr;
#ifdef _WIN32
    HMODULE module = GetModuleHandleA(path);
    FARPROC symbol = module == nullptr ? nullptr : GetProcAddress(module, "get_jni_env_from_jvm");
#else
    void* module = dlopen(path, RTLD_NOW | RTLD_NOLOAD);
    void* symbol = module == nullptr ? nullptr : dlsym(module, "get_jni_env_from_jvm");
#endif
    static_assert(sizeof(function) == sizeof(symbol), "Native function pointer width");
    std::memcpy(&function, &symbol, sizeof(function));
#ifndef _WIN32
    if (module != nullptr) dlclose(module);
#endif
    env->ReleaseStringUTFChars(library, path);
    return function;
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_tel_schich_libdatachannel_NativeLibraryLoadingTest_00024Probe_loadForeignLibrary(
        JNIEnv* env, jclass target, jstring library) {
    (void) target;
    const char* path = env->GetStringUTFChars(library, nullptr);
    if (path == nullptr) return 0;
#ifdef _WIN32
    auto module = LoadLibraryA(path);
#else
    auto module = dlopen(path, RTLD_NOW | RTLD_GLOBAL);
#endif
    env->ReleaseStringUTFChars(library, path);
    return static_cast<jlong>(reinterpret_cast<intptr_t>(module));
}

extern "C" JNIEXPORT jlong JNICALL
Java_tel_schich_libdatachannel_NativeLibraryLoadingTest_00024Probe_foreignDependencyCheck(
        JNIEnv* env, jclass target, jlong handle, jstring check) {
    (void) target;
    if (handle == 0) return 0;
    const char* name = env->GetStringUTFChars(check, nullptr);
    if (name == nullptr) return 0;
#ifdef _WIN32
    auto symbol = GetProcAddress(reinterpret_cast<HMODULE>(static_cast<intptr_t>(handle)),
                                 name);
#else
    auto symbol = dlsym(reinterpret_cast<void*>(static_cast<intptr_t>(handle)),
                        name);
#endif
    env->ReleaseStringUTFChars(check, name);
    unsigned long (*version)() = nullptr;
    static_assert(sizeof(version) == sizeof(symbol), "Native function pointer width");
    std::memcpy(&version, &symbol, sizeof(version));
    return version == nullptr ? 0 : static_cast<jlong>(version());
}

extern "C" JNIEXPORT jint JNICALL
Java_tel_schich_libdatachannel_NativeThreadAttachmentTest_capture(JNIEnv* env, jclass target,
                                                                 jstring library, jint count) {
    if (count <= 0 || count > 32) return -1;
    EnvironmentFunction environment = environment_function(env, library);
    JavaVM* vm = nullptr;
    if (environment == nullptr || env->GetJavaVM(&vm) != JNI_OK) return -2;
    jclass callback_class = static_cast<jclass>(env->NewGlobalRef(target));
    if (callback_class == nullptr) return -3;
    jmethodID callback = env->GetStaticMethodID(target, "captureCurrent", "()V");
    if (callback == nullptr) {
        env->DeleteGlobalRef(callback_class);
        return -4;
    }
    std::atomic<int> failures{0};
    std::vector<std::thread> threads;
    try {
        for (int i = 0; i < count; ++i) {
            threads.emplace_back([&]() {
                JNIEnv* current = environment(vm);
                if (current == nullptr || environment(vm) != current) {
                    ++failures;
                    return;
                }
                current->CallStaticVoidMethod(callback_class, callback);
                if (current->ExceptionCheck()) {
                    current->ExceptionClear();
                    ++failures;
                }
            });
        }
    } catch (...) {
        ++failures;
    }
    for (std::thread& thread : threads) thread.join();
    env->DeleteGlobalRef(callback_class);
    return failures == 0 && threads.size() == static_cast<size_t>(count) ? count : -5;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_tel_schich_libdatachannel_NativeThreadAttachmentTest_alreadyAttached(JNIEnv* env, jclass target,
                                                                         jstring library) {
    (void) target;
    EnvironmentFunction environment = environment_function(env, library);
    JavaVM* vm = nullptr;
    return environment != nullptr && env->GetJavaVM(&vm) == JNI_OK && environment(vm) == env
                   ? JNI_TRUE : JNI_FALSE;
}
