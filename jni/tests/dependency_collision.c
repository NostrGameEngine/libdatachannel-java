#include <stdint.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>

#ifdef _WIN32
#define EXPORT __declspec(dllexport)
#else
#define EXPORT __attribute__((visibility("default")))
#endif

/* An unrelated library owns these names. Calling them from JNI is a failure. */
static void collision(void) {
    fputs("FOREIGN_CRYPTO_CALLED_BY_JNI\n", stderr);
    _Exit(86);
}
EXPORT unsigned long OpenSSL_version_num(void) { return 0x7eed0001UL; }
EXPORT unsigned long foreign_crypto_self_check(void) { return OpenSSL_version_num(); }
EXPORT int OPENSSL_init_ssl(uint64_t options, const void* settings) {
    (void) options; (void) settings; collision(); return 0;
}
EXPORT int OPENSSL_init_crypto(uint64_t options, const void* settings) {
    (void) options; (void) settings; collision(); return 0;
}
EXPORT void* SSL_CTX_new(const void* method) { (void) method; collision(); return NULL; }

/* A foreign allocator must remain independent in both library load orders. */
static _Thread_local int allocator_self_test;
static _Thread_local unsigned int allocator_calls;

static void check_allocator_call(void) {
    if (!allocator_self_test) {
        fputs("FOREIGN_ALLOCATOR_CALLED_BY_JNI\n", stderr);
        _Exit(87);
    }
    ++allocator_calls;
}
EXPORT void* mi_malloc(size_t size) {
    check_allocator_call(); return malloc(size);
}
EXPORT void* mi_calloc(size_t count, size_t size) {
    check_allocator_call(); return calloc(count, size);
}
EXPORT void* mi_realloc(void* pointer, size_t size) {
    check_allocator_call(); return realloc(pointer, size);
}
EXPORT void mi_free(void* pointer) {
    check_allocator_call(); free(pointer);
}
EXPORT unsigned long foreign_allocator_self_check(void) {
    allocator_self_test = 1;
    allocator_calls = 0;
    unsigned char* memory = mi_malloc(16);
    unsigned char* zeroed = mi_calloc(4, 8);
    if (memory == NULL || zeroed == NULL) _Exit(88);
    memset(memory, 0x5a, 16);
    memory = mi_realloc(memory, 32);
    if (memory == NULL) _Exit(88);
    int valid = 1;
    for (size_t i = 0; i < 32; ++i) {
        if (zeroed[i] != 0 || (i < 16 && memory[i] != 0x5a)) valid = 0;
    }
    mi_free(memory);
    mi_free(zeroed);
    allocator_self_test = 0;
    return valid && allocator_calls == 5 ? 0x7eed0002UL : 0;
}
