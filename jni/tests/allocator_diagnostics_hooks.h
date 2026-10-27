#ifndef LIBDATACHANNEL_ALLOCATOR_DIAGNOSTICS_HOOKS_H
#define LIBDATACHANNEL_ALLOCATOR_DIAGNOSTICS_HOOKS_H

/* Load the pinned allocator's definitions before adapting its documented
 * tracking macros. This header is forced only into the diagnostic Mi target. */
#include <mimalloc/internal.h>

#ifdef __cplusplus
extern "C" {
#endif
void libdatachannel_trace_allocate(const void* memory, size_t requested);
void libdatachannel_trace_free(const void* memory);
void libdatachannel_trace_align(const void* memory);
#ifdef __cplusplus
}
#endif

#undef MI_TRACK_ENABLED
#undef MI_TRACK_HEAP_DESTROY
#undef MI_TRACK_TOOL
#undef mi_track_malloc_size
#undef mi_track_free_size
#undef mi_track_align
#define MI_TRACK_ENABLED 1
#define MI_TRACK_HEAP_DESTROY 1
#define MI_TRACK_TOOL "libdatachannel-ci"
#define mi_track_malloc_size(p, requested, size, zero) libdatachannel_trace_allocate(p, requested)
#define mi_track_free_size(p, size) libdatachannel_trace_free(p)
#define mi_track_align(p, aligned, offset, size) libdatachannel_trace_align(aligned)

#endif
