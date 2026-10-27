#include <jni.h>
#include <mimalloc.h>
#include <rtc/rtc.h>
#include <stdatomic.h>
#include <stdint.h>

/* No tracking callback allocates, logs, unwinds stacks or calls the JVM. These
 * counters are private to this JNI image, including before JNI_OnLoad. */
static _Atomic uint64_t allocations;
static _Atomic uint64_t releases;
static _Atomic uint64_t requested_bytes;
static _Atomic uint64_t violations;
static _Atomic uint64_t alignments;

static void check_owner(const void* memory) {
    if (memory && !mi_is_in_heap_region(memory))
        atomic_fetch_add_explicit(&violations, 1, memory_order_relaxed);
}

void libdatachannel_trace_allocate(const void* memory, size_t requested) {
    if (!memory) return;
    check_owner(memory);
    atomic_fetch_add_explicit(&allocations, 1, memory_order_relaxed);
    atomic_fetch_add_explicit(&requested_bytes, requested, memory_order_relaxed);
}

void libdatachannel_trace_free(const void* memory) {
    if (!memory) return;
    check_owner(memory);
    atomic_fetch_add_explicit(&releases, 1, memory_order_relaxed);
}

void libdatachannel_trace_align(const void* memory) {
    check_owner(memory);
    atomic_fetch_add_explicit(&alignments, 1, memory_order_relaxed);
}

JNIEXPORT jlongArray JNICALL
Java_tel_schich_libdatachannel_NativeAllocatorDiagnosticsTest_snapshot(JNIEnv* env, jclass type) {
    (void) type;
    jlong values[] = {
        (jlong) atomic_load_explicit(&allocations, memory_order_relaxed),
        (jlong) atomic_load_explicit(&releases, memory_order_relaxed),
        (jlong) atomic_load_explicit(&requested_bytes, memory_order_relaxed),
        (jlong) atomic_load_explicit(&violations, memory_order_relaxed),
        (jlong) atomic_load_explicit(&alignments, memory_order_relaxed)
    };
    jlongArray result = (*env)->NewLongArray(env, 5);
    if (result) (*env)->SetLongArrayRegion(env, result, 0, 5, values);
    return result;
}

JNIEXPORT void JNICALL
Java_tel_schich_libdatachannel_NativeAllocatorDiagnosticsTest_checkForeignBuffer(
        JNIEnv* env, jclass type, jobject memory) {
    (void) type;
    /* Deliberately inject an ownership violation without freeing JVM memory. */
    check_owner((*env)->GetDirectBufferAddress(env, memory));
}

JNIEXPORT void JNICALL
Java_tel_schich_libdatachannel_NativeAllocatorDiagnosticsTest_finish(JNIEnv* env, jclass type) {
    (void) env;
    (void) type;
    rtcCleanup();
}
