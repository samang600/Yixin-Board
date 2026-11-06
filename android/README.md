Yixin Board for Android
=======================

An Android port of Yixin-Board: a gomoku / renju board with three AI engines.

The original Yixin engine is a closed-source Windows program, so it cannot run on Android.
The app instead offers (menu → AI engine), falling back automatically from left to right:

* **Rapfi** (default, all board sizes and rules): the engine by dhbloo and contributors
  (fork https://github.com/dotb1ue/rapfi, git submodule `third_party/rapfi`) with its NNUE
  (mix9svq) networks (`third_party/rapfi-networks`, CC0). It is built with the NDK as an executable
  packaged as `librapfi.so` and run as a child process speaking the Gomocup/Yixin protocol
  (`engine/RapfiEngine.kt`, `RapfiRuntime.kt`); the weights (~40 MB) are unpacked from the APK on
  first use. Built for arm64-v8a (NEON) and x86_64 (SSE); Rapfi has no 32-bit ARM support, so those
  devices use SlowRenju. Uses up to 4 search threads; standard/renju boards other than 15×15 use
  Rapfi's classical evaluation (no network for those sizes).
* **SlowRenju** (15×15): the C++ engine by Tianyi Hao (Gomocup participant since 2013),
  built with the NDK and called through JNI. Sources in `app/src/main/cpp/slowrenju`, with the
  changes listed in `MODIFICATIONS.md` there. Thinking is time-limited (the engine is stopped at the
  chosen thinking time and plays its best move so far).
* **Built-in** (all board sizes, pure Kotlin, `app/src/main/java/io/github/yixinboard/engine`):
  alpha-beta search with iterative deepening, five-threat extensions, a VCF solver and an incremental
  window-based evaluation. Used automatically on boards other than 15×15.

SlowRenju speed-ups
-------------------

Measured on 12 benchmark midgame positions (desktop x86-64, single thread), with the requirement
that the search result stays identical (same moves and node counts):

| Change | Effect |
|---|---|
| Precomputed neighbourhood / gather tables, lookup instead of `%` | −27% instructions |
| `-O3` + link-time optimisation (ThinLTO in the app) | ~15–20% faster |
| Together | ~1.3× faster wall-clock (6.8 s → 5.2 s at node level 4) |
| Hash table 384 MB → 48 MB | same moves, node counts within 1%; avoids memory pressure and the slow 384 MB clear on phones |

The engine is single-threaded with global state, so multi-core speed-ups would need a larger rewrite.

Features
--------

* Rules: freestyle (five or more), standard (exactly five), renju (forbidden moves for black:
  double-three, double-four, overline; detection includes the "fake three" recursion)
* AI can play black, white, both or neither (two-player mode); "AI move" button makes the AI play the
  current side once, and stops/plays immediately while it is thinking
* Undo / redo, hint, move numbers, coordinates, forbidden-point markers, tap-twice-to-place
* Board sizes 13, 15, 17, 19, 20; AI thinking time 0.5 s to 30 s
* Copy / paste game records (`h8i9j10…`, columns a.., rows counted from the bottom)
* The game and settings are saved automatically
* English, Korean (한국어) and Simplified Chinese (简体中文) UI; portrait and landscape layouts

Install
-------

A ready-built APK is in `../release/android/YixinBoard.apk` (signed with a debug key, Android 8.0+).
Enable "install unknown apps" on the phone and open the file.

Build
-----

Requirements: JDK 17+, Android SDK (platform 35), NDK 27.2.12479018 and CMake 3.22.1
(`sdkmanager "ndk;27.2.12479018" "cmake;3.22.1"`), and the Rapfi submodules:

```
git submodule update --init android/third_party/rapfi android/third_party/rapfi-networks
```

(not `--recursive`: Rapfi's own submodules are not needed). Put `sdk.dir=/path/to/sdk` in `local.properties`
or set `ANDROID_HOME`, then:

```
./gradlew testDebugUnitTest   # rules, engine and Robolectric UI tests
./gradlew assembleRelease     # app/build/outputs/apk/release/app-release.apk
```

The release build is signed with the debug key so it installs directly; configure your own
signing key before publishing it to a store.

The native engine tests run on the desktop JVM against host builds and are skipped without them.
Rapfi (`RapfiEngineTest`, uses the app's config and the bundled weights):

```
cmake -S third_party/rapfi/Rapfi -B /tmp/rapfi -DCMAKE_BUILD_TYPE=Release -DUSE_SSE=ON -DUSE_AVX2=ON -DNO_COMMAND_MODULES=ON
cmake --build /tmp/rapfi
RAPFI_BIN=/tmp/rapfi/pbrain-rapfi ./gradlew testDebugUnitTest --tests '*RapfiEngineTest*'
```

SlowRenju (`SlowRenjuTest`):

```
cd app/src/main/cpp && g++ -O3 -shared -fPIC -fvisibility=hidden -DANDROID -DHASH_BITS=20 -w \
  -I<shim dir with an empty android/log.h> -I$JAVA_HOME/include -I$JAVA_HOME/include/linux \
  -o /tmp/sr/libslowrenju.so slowrenju_jni.cpp slowrenju/*/*.cpp
SLOWRENJU_LIB_DIR=/tmp/sr ENGINE_MATCH_GAMES=6 ./gradlew testDebugUnitTest --tests '*SlowRenjuTest*'
```

License
-------

The Android app includes Rapfi and SlowRenju, both licensed under the GNU GPL v3, so the app as a
whole is distributed under the **GNU General Public License v3** (`app/src/main/cpp/slowrenju/LICENSE`,
`third_party/rapfi/Copying.txt`). The Rapfi network weights are CC0.
The original Yixin-Board code it is based on is under the Simplified BSD License (`../LICENSE.md`),
which is compatible.
