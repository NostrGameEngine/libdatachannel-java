/* Compiled as part of mimalloc-static, with that target's actual definitions. */
#ifndef MI_SECURE
#error The private desktop allocator must be compiled in secure mode
#endif
int private_allocator_secure_level(void) { return MI_SECURE; }
