#ifndef LIBDATACHANNEL_GLOBAL_JVM_H
#define LIBDATACHANNEL_GLOBAL_JVM_H

#include <jni.h>

#ifdef __cplusplus
extern "C" {
#endif

JNIEnv* get_jni_env(void);
JNIEXPORT JNIEnv* get_jni_env_from_jvm(JavaVM* jvm);
int initialize_jni_thread_tracking(void);
int register_jni_thread(JavaVM* jvm);
void release_jni_thread_tracking(void);

#ifdef __cplusplus
}
#endif

#endif
