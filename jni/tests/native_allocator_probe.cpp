#include <jni.h>
#include <mimalloc.h>
#include <openssl/crypto.h>
#include <rtc/message.hpp>
#include <functional>
#include <iomanip>
#include <memory>
#include <new>
#include <limits>
#include <sstream>
#include <string>
#include <vector>

extern "C" int datachannel_java_allocator_probe(void);
extern "C" int juice_static_allocator_probe(void);
extern "C" int usrsctp_allocator_probe(void);
extern "C" int private_allocator_secure_level(void);

extern "C" JNIEXPORT jint JNICALL
Java_tel_schich_libdatachannel_NativeAllocatorTest_allocationCoverage(JNIEnv* env, jclass, jobject application_buffer) {
    try {
        int result = 0;
        if (datachannel_java_allocator_probe()) result |= 1;
        auto scalar = std::make_unique<unsigned char>();
        auto array = std::make_unique<unsigned char[]>(8192);
        auto aligned = ::operator new(4096, std::align_val_t(128), std::nothrow);
        if (scalar && array && aligned && mi_is_in_heap_region(scalar.get()) &&
            mi_is_in_heap_region(array.get()) && mi_is_in_heap_region(aligned)) result |= 2;
        ::operator delete(aligned, std::align_val_t(128), std::nothrow);
        auto message = rtc::make_message(65536);
        if (mi_is_in_heap_region(message.get()) && mi_is_in_heap_region(message->data())) result |= 4;
        std::string text(4096, 'x');
        bool owned = mi_is_in_heap_region(text.data());
        for (int i = 0; i < 20; ++i) text.append(5000, 'y');
        std::stringstream stream;
        stream << text;
        std::vector<std::string> copies(20, stream.str());
        owned = owned && mi_is_in_heap_region(text.data()) && mi_is_in_heap_region(copies.data()) && mi_is_in_heap_region(copies[0].data());
        auto shared = std::make_shared<std::string>(copies[0]);
        std::function<size_t()> callback = [shared, copies] { return shared->size() + copies.size(); };
        // Exceed libc++'s stack formatting buffer and exercise asprintf_l on Mac.
        std::ostringstream numbers;
        numbers << std::fixed << std::setprecision(512) << 1.25;
        const std::string formatted = numbers.str();
        if (owned && mi_is_in_heap_region(shared.get()) && callback() == text.size() + copies.size() &&
            formatted.size() == 514 && formatted.substr(0, 4) == "1.25" &&
            formatted.find_first_not_of('0', 4) == std::string::npos &&
            mi_is_in_heap_region(formatted.data())) result |= 8;
        if (juice_static_allocator_probe()) result |= 16;
        if (usrsctp_allocator_probe()) result |= 32;
        void* crypto = OPENSSL_malloc(4096);
        bool crypto_owned = crypto && mi_is_in_heap_region(crypto);
        crypto = OPENSSL_realloc(crypto, 8192);
        if (crypto_owned && crypto && mi_is_in_heap_region(crypto)) result |= 64;
        OPENSSL_free(crypto);
        if (private_allocator_secure_level() >= 4) result |= 128;
        void* application_memory = env->GetDirectBufferAddress(application_buffer);
        if (application_memory && !mi_is_in_heap_region(application_memory)) result |= 256;
        volatile size_t impossible_size = std::numeric_limits<size_t>::max();
        bool throws_bad_alloc = false;
        try {
            void* impossible = ::operator new(impossible_size);
            ::operator delete(impossible);
        } catch (const std::bad_alloc& error) {
            throws_bad_alloc = true;
            if (mi_is_in_heap_region(&error)) result |= 1024;
        }
        void* nothrow_result = ::operator new(impossible_size, std::nothrow);
        if (throws_bad_alloc && !nothrow_result) result |= 512;
        ::operator delete(nothrow_result, std::nothrow);
        return result;
    } catch (const std::exception& error) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what());
        return 0;
    }
}
