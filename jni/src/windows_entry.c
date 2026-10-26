#include <windows.h>

// JNI owns its CRT entry point; embedded dependencies use their static initialization.
// JVM initialization belongs in JNI_OnLoad, after the loader has finished.
BOOL WINAPI DllMain(HINSTANCE instance, DWORD reason, LPVOID reserved) {
    (void) instance;
    (void) reason;
    (void) reserved;
    return TRUE;
}
