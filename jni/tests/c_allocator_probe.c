#include <stdlib.h>
#include <string.h>
#include <mimalloc.h>

int LIBDATACHANNEL_ALLOCATOR_PROBE(void) {
    unsigned char* memory = malloc(4096);
    unsigned char* zeros = calloc(32, 64);
    char* text = strdup("allocation ownership");
    if (!memory || !zeros || !text) abort();
    int valid = mi_is_in_heap_region(memory) && mi_is_in_heap_region(zeros) && mi_is_in_heap_region(text);
    memset(memory, 0x5a, 4096);
    memory = realloc(memory, 8192);
    if (!memory) abort();
    valid = valid && mi_is_in_heap_region(memory) && memory[4095] == 0x5a && zeros[2047] == 0;
    free(memory);
    free(zeros);
    free(text);
    return valid;
}
