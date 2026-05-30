#include "global_jvm.h"
#include "jni-c-to-java.h"
#include <jni.h>
#include <pthread.h>
#include <rtc/rtc.h>
#ifdef __APPLE__
#include <TargetConditionals.h>
#endif

#define JNI_VERSION JNI_VERSION_1_6

static JavaVM* global_JVM;
static pthread_key_t thread_key;
static pthread_once_t thread_key_once = PTHREAD_ONCE_INIT;
static volatile jint jvm_unloading = 0;
static volatile jint jvm_loaded = 0;

void detach_thread();

static void create_thread_key() {
    pthread_key_create(&thread_key, detach_thread);
}

void detach_thread() {
    JavaVM* jvm = pthread_getspecific(thread_key);
    if (jvm != NULL) {
        (*jvm)->DetachCurrentThread(jvm);
    }
}

JNIEnv* get_jni_env_from_jvm(JavaVM* jvm) {
    JNIEnv* env;
    jint result = (*jvm)->GetEnv(jvm, (void**) &env, JNI_VERSION);
    if (result == JNI_EDETACHED) {
        result = (*jvm)->AttachCurrentThreadAsDaemon(jvm, (void**) &env, NULL);
        if (result == JNI_OK) {
            pthread_setspecific(thread_key, jvm);
        }
    }
    if (result != JNI_OK) {
        return NULL;
    }
    return env;
}

JNIEnv* get_jni_env() {
    // make sure it's initialized and not in unload phase
    if (global_JVM == NULL || jvm_unloading) {
        return NULL;
    }
    return get_jni_env_from_jvm(global_JVM);
}

void logger_callback(rtcLogLevel level, const char* message) {
    if (message == NULL) {
        return;
    }
    JNIEnv* env = get_jni_env();
    if (env != NULL) {
        call_tel_schich_libdatachannel_LibDataChannel_log_cstr(env, level, message);
    }
}

static jint initialize_jni(JavaVM* jvm) {
    if (jvm_loaded && global_JVM == jvm && !jvm_unloading) {
        return JNI_VERSION;
    }
    pthread_once(&thread_key_once, create_thread_key);
    global_JVM = jvm;
    jvm_unloading = 0;
    JNIEnv* env = get_jni_env_from_jvm(jvm);
    module_OnLoad(env);
#if defined(TARGET_OS_IPHONE) && TARGET_OS_IPHONE
    /*
     * The iOS static-link path runs inside GraalVM/libJGLIOS. Re-entering Java
     * from the native logger while libdatachannel initializes can deadlock the
     * first peer creation, so keep logging on the library default path there.
     */
    rtcSetThreadPoolSize(2);
#else
    rtcInitLogger(RTC_LOG_VERBOSE, &logger_callback);
    rtcPreload();
#endif
    jvm_loaded = 1;
    return JNI_VERSION;
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* jvm, void* reserved) {
    (void) reserved;
    return initialize_jni(jvm);
}

JNIEXPORT void JNICALL Java_tel_schich_libdatachannel_LibDataChannel_initializeNative(JNIEnv* env, jclass clazz) {
    (void) clazz;
    JavaVM* jvm = NULL;
    if ((*env)->GetJavaVM(env, &jvm) != JNI_OK || jvm == NULL) {
        jclass exception_class = (*env)->FindClass(env, "java/lang/IllegalStateException");
        if (exception_class != NULL) {
            (*env)->ThrowNew(env, exception_class, "Unable to resolve JavaVM for libdatachannel initialization");
        }
        return;
    }
    initialize_jni(jvm);
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM* jvm, void* reserved) {
    (void) reserved;
    jvm_unloading = 1;
    rtcCleanup();
    JNIEnv* env = get_jni_env_from_jvm(jvm);
    module_OnUnload(env);
    global_JVM = NULL;
    jvm_loaded = 0;
}
