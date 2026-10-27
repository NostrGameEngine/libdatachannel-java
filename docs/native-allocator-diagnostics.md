# Native allocator diagnostics

The `Allocator diagnostics` workflow builds an instrumented desktop JNI library
on Linux, Windows, and both macOS architectures. Instrumentation is off by
default and absent from normal native artifacts. The workflow rebuilds without
instrumentation and verifies that its diagnostic JNI methods disappear.

To reproduce a CI run with the normal native build prerequisites installed:

```sh
./gradlew -PnativeAllocatorDiagnostics=true nativeAllocatorDiagnostics
python tools/test_allocator_audit.py
python tools/audit_allocator_imports.py build/dockcross/host/native/libdatachannel-java.so
```

Use the `.dylib` or `.dll` filename on macOS or Windows. Rebuild with
`./gradlew test` afterwards to rebuild and repackage without instrumentation.

The adapter uses the pinned mimalloc version's allocation/free tracking macros.
Callbacks count successful allocations, requested bytes, releases and alignment
adjustments, and check ownership against the private allocator instance. They do
not allocate memory, log, unwind stacks or replace the process allocator.
Counters cover initialization, the existing component ownership probes,
repeated bidirectional DTLS transfers with 64-byte, 4-KiB and 64-KiB messages,
four concurrent peer pairs, and explicit native cleanup. Each workload must
exercise allocations and frees without ownership violations. Message payloads
and ordering are checked. A separate JVM injects a JVM-owned pointer and must
fail the ownership rule. The foreign pointer is never freed by mimalloc.

An independent final-binary audit rejects imports of standard C heap functions,
allocating formatting functions, C++ new/delete and exception allocation APIs.
A genuinely compiled library importing system malloc is its negative control.
This complements tracing: a call that bypasses mimalloc would not reach its
tracking callbacks. The audit checks direct imports, not arbitrary calls made
through dynamically discovered function pointers.

Reports are uploaded as `allocator-diagnostics-*` artifacts. JSONL files contain
per-phase counter deltas, final totals and cumulative ownership violations. Counts are not
leak reports: runtime caches and allocator bookkeeping may survive a phase,
and snapshots are taken while background workers can still allocate.

The scope is the desktop native heap controlled by the binding. JVM/application
buffers and system APIs with their own matching release functions remain owned
by their respective platforms. Android and iOS use their platform allocator and
are not instrumented. Passing these workloads establishes observed coverage and
direct-import isolation, not a proof for every possible execution path.
