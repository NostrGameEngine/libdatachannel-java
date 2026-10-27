#define LIBDATACHANNEL_ALLOCATOR_IMPLEMENTATION 1
#include <mimalloc.h>

#ifdef __APPLE__
#include <errno.h>
#include <stdarg.h>
#include <stdio.h>
#include <xlocale.h>
#endif

/* These definitions are localized by the JNI export allow-list. They also
 * resolve allocation calls in the embedded static C++ runtime and OpenSSL. */
void* malloc(size_t size) { return mi_malloc(size); }
void* calloc(size_t count, size_t size) { return mi_calloc(count, size); }
void* realloc(void* memory, size_t size) { return mi_realloc(memory, size); }
void free(void* memory) { mi_free(memory); }
char* strdup(const char* text) { return mi_strdup(text); }
char* strndup(const char* text, size_t size) { return mi_strndup(text, size); }
void* aligned_alloc(size_t alignment, size_t size) { return mi_aligned_alloc(alignment, size); }
int posix_memalign(void** memory, size_t alignment, size_t size) {
    return mi_posix_memalign(memory, alignment, size);
}

#ifdef __APPLE__
/* libc++ uses this for floating-point formatting. Its result is released by
 * the private C++ runtime, so the allocation must have the same owner. */
int asprintf_l(char** output, locale_t locale, const char* format, ...) {
    *output = NULL;
    va_list arguments;
    va_start(arguments, format);
    va_list measure;
    va_copy(measure, arguments);
    int size = vsnprintf_l(NULL, 0, locale, format, measure);
    va_end(measure);
    if (size < 0) {
        va_end(arguments);
        return -1;
    }
    char* memory = mi_malloc((size_t) size + 1);
    if (!memory) {
        va_end(arguments);
        errno = ENOMEM;
        return -1;
    }
    int written = vsnprintf_l(memory, (size_t) size + 1, locale, format, arguments);
    va_end(arguments);
    if (written < 0 || written > size) {
        mi_free(memory);
        return -1;
    }
    *output = memory;
    return written;
}
#endif

#ifdef _WIN32
/* MinGW's static runtime can also use CRT import-address references.
 * Resolve its import-address symbols at link time inside this DLL; no process
 * import table or CRT implementation is changed. */
void* (__cdecl* __imp_malloc)(size_t) = mi_malloc;
void* (__cdecl* __imp_calloc)(size_t, size_t) = mi_calloc;
void* (__cdecl* __imp_realloc)(void*, size_t) = mi_realloc;
void (__cdecl* __imp_free)(void*) = mi_free;
char* _strdup(const char* text) { return mi_strdup(text); }
char* (__cdecl* __imp__strdup)(const char*) = _strdup;
char* (__cdecl* __imp_strdup)(const char*) = strdup;
#endif
