#include <cstdio>
#include <cstdlib>
#include <new>

#ifdef _WIN32
#define EXPORT __declspec(dllexport)
#else
#define EXPORT __attribute__((visibility("default")))
#endif

static thread_local bool self_test;
static thread_local unsigned calls;

static void check_call() {
    if (!self_test) {
        std::fputs("FOREIGN_CPP_ALLOCATOR_CALLED_BY_JNI\n", stderr);
        std::_Exit(89);
    }
    ++calls;
}

EXPORT void* operator new(std::size_t size) {
    check_call();
    if (void* memory = std::malloc(size)) return memory;
    throw std::bad_alloc();
}
EXPORT void* operator new[](std::size_t size) { return ::operator new(size); }
EXPORT void operator delete(void* memory) noexcept { check_call(); std::free(memory); }
EXPORT void operator delete[](void* memory) noexcept { ::operator delete(memory); }
EXPORT void operator delete(void* memory, std::size_t) noexcept { ::operator delete(memory); }
EXPORT void operator delete[](void* memory, std::size_t) noexcept { ::operator delete(memory); }

extern "C" EXPORT unsigned long foreign_cpp_allocator_self_check() {
    self_test = true;
    calls = 0;
    // Explicit calls make the checks observable even with allocation elision.
    void* memory = ::operator new(8192);
    void* array = ::operator new[](16384);
    ::operator delete(memory);
    ::operator delete[](array);
    bool valid = calls == 4;
    self_test = false;
    return valid ? 0x7eed0003UL : 0;
}
