#include "global_jvm.h"
#ifdef _WIN32
#include <windows.h>
#include <new>
#endif

namespace {

#ifdef _WIN32
DWORD attachment_key = FLS_OUT_OF_INDEXES;

struct ThreadAttachment {
    JavaVM* vm;
    DWORD owner;
};

void NTAPI detach_thread(void* value) {
    auto* attachment = static_cast<ThreadAttachment*>(value);
    // FlsFree can invoke callbacks for other fibers/threads on its caller.
    // Only the owning thread may detach its JVM attachment.
    if (attachment->owner == GetCurrentThreadId()) {
        JNIEnv* env = nullptr;
        if (attachment->vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK) {
            attachment->vm->DetachCurrentThread();
        }
    }
    delete attachment;
}
#else
struct ThreadAttachment {
    JavaVM* vm = nullptr;

    ~ThreadAttachment() {
        if (vm != nullptr) {
            vm->DetachCurrentThread();
        }
    }
};
#endif

} // namespace

extern "C" int initialize_jni_thread_tracking(void) {
#ifdef _WIN32
    if (attachment_key != FLS_OUT_OF_INDEXES) return 1;
    attachment_key = FlsAlloc(detach_thread);
    return attachment_key != FLS_OUT_OF_INDEXES;
#else
    return 1;
#endif
}

extern "C" int register_jni_thread(JavaVM* jvm) {
#ifdef _WIN32
    // MinGW runs C++ thread_local destructors from DLL_THREAD_DETACH under
    // the loader lock. JNI detachment can wait for a JVM safepoint, creating
    // a lock cycle with JVM threads that are exiting. FLS callbacks avoid
    // that DLL notification path. Keep the context independent of CRT TLS.
    if (attachment_key == FLS_OUT_OF_INDEXES) return 0;
    auto* attachment = static_cast<ThreadAttachment*>(FlsGetValue(attachment_key));
    if (attachment != nullptr) {
        attachment->vm = jvm;
        return 1;
    }
    attachment = new (std::nothrow) ThreadAttachment{jvm, GetCurrentThreadId()};
    if (attachment == nullptr) return 0;
    if (!FlsSetValue(attachment_key, attachment)) {
        delete attachment;
        return 0;
    }
#else
    // C++ thread-local destruction runs before pthread-key cleanup on Darwin.
    // A pthread destructor is too late there: HotSpot has already cleared its
    // thread-local JVM state, so DetachCurrentThread cannot release the thread.
    // Register only attachments created by this library; Java-owned threads
    // and attachments belonging to another native caller keep their owner.
    static thread_local ThreadAttachment attachment;
    attachment.vm = jvm;
#endif
    return 1;
}

extern "C" void release_jni_thread_tracking(void) {
#ifdef _WIN32
    // JNI_OnUnload first stops native workers, then unregisters the callback
    // while this DLL and its private allocator are still loaded.
    if (attachment_key != FLS_OUT_OF_INDEXES) {
        DWORD key = attachment_key;
        attachment_key = FLS_OUT_OF_INDEXES;
        FlsFree(key);
    }
#endif
}
