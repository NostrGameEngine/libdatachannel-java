#ifndef LIBDATACHANNEL_JNI_MIMALLOC_REDIRECT_H
#define LIBDATACHANNEL_JNI_MIMALLOC_REDIRECT_H

#if defined(LIBDATACHANNEL_PRIVATE_ALLOCATOR) && !defined(LIBDATACHANNEL_ALLOCATOR_IMPLEMENTATION)
/* Parse the platform declarations before the function-like redirect macros. */
#if defined(_WIN32) && !defined(_CRT_RAND_S)
/* usrsctp enables rand_s before its normal stdlib include. Preserve that
 * declaration when this forced preamble includes stdlib first. */
#define _CRT_RAND_S 1
#endif
#include <stdlib.h>
#include <string.h>
#include <wchar.h>
#ifdef _WIN32
#include <malloc.h>
#endif
#include <mimalloc-override.h>
#endif

#endif
