# SlowRenju in Yixin Board for Android

This directory contains the SlowRenju engine by Tianyi Hao
(https://github.com/samang600/SlowRenju, commit 41007cf, originally https://www.wind23.com/gomokuai.html),
distributed under the GNU General Public License v3 (see `LICENSE`).

Only the engine sources are included (`Common/main.cpp`, `Common/manual.cpp` and the original
Android JNI file are not used; the app has its own bridge in `../slowrenju_jni.cpp`).

## Modifications (2026-11)

Portability (no change in behaviour):
- `#include "..\Headers\game.h"` changed to forward slashes so the code builds outside Windows.
- `Headers/game.h`: added `#include <cstring>` (needed for memset/memcpy/memcmp outside MSVC).
- `VCF/VCF.cpp`: the hash map allocator type is `pair<const wstring,int>` (required by libc++).

Speed (search results are unchanged: identical moves and node counts on the benchmark positions):
- `AI/AIx.cpp`: the per-node "covered" neighbourhood uses precomputed in-board cell lists
  instead of 32 bounds-checked offsets per stone.
- `Value/ValueW.cpp`: `value()` uses precomputed neighbour tables and direct array access,
  keeping the same visiting and floating-point summation order.
- `Value/ValueWide.cpp`: `ComputeShape1b()` gathers only the 11 cells `line::shape()` can read via a
  precomputed index table, instead of copying a whole board line; `temp % L6` uses a lookup table.

Memory:
- `AI/Hash.cpp`: transposition table size is configurable with `HASH_BITS` (original: 23 bits =
  384 MB). The app builds it with 20 bits (48 MB), which suits phones; on benchmark positions this
  gives the same moves with node counts within 1%.

Build note: the library must be built with `-fvisibility=hidden` (see `../CMakeLists.txt`). The engine
defines globals such as `times`, which also names a libc function; with default visibility the
shared library resolves them to libc and crashes when the engine writes to them.
