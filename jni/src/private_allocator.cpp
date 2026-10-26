#include <mimalloc-new-delete.h>
#include <openssl/crypto.h>

namespace {
void* crypto_malloc(size_t size, const char*, int) { return mi_malloc(size); }
void* crypto_realloc(void* memory, size_t size, const char*, int) {
    return mi_realloc(memory, size);
}
void crypto_free(void* memory, const char*, int) { mi_free(memory); }
}

extern "C" int initialize_private_allocator(void) {
    CRYPTO_malloc_fn allocate;
    CRYPTO_realloc_fn resize;
    CRYPTO_free_fn release;
    CRYPTO_get_mem_functions(&allocate, &resize, &release);
    if (allocate == crypto_malloc && resize == crypto_realloc && release == crypto_free) return 1;
    return CRYPTO_set_mem_functions(crypto_malloc, crypto_realloc, crypto_free);
}
