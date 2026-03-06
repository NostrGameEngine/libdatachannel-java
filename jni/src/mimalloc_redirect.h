#ifndef LIBDATACHANNEL_JNI_MIMALLOC_REDIRECT_H
#define LIBDATACHANNEL_JNI_MIMALLOC_REDIRECT_H

#if !defined(__ANDROID__)
#include <mimalloc.h>


#define malloc mi_malloc
#define calloc mi_calloc
#define realloc mi_realloc
#define free mi_free
#endif

#endif
