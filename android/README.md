Yixin Board for Android
=======================

An Android port of Yixin-Board: a gomoku / renju board with a built-in AI.

The original Yixin engine is a closed-source Windows program, so it cannot run on Android.
This app ships its own engine instead (pure Kotlin, `app/src/main/java/io/github/yixinboard/engine`):

* Alpha-beta (negamax) search with iterative deepening and threat (five) extensions
* VCF solver (victory by continuous fours) used to attack and, at the root, to defend
* Incremental window-based evaluation

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

Requirements: JDK 17+, Android SDK (platform 35). Put `sdk.dir=/path/to/sdk` in `local.properties`
or set `ANDROID_HOME`, then:

```
./gradlew testDebugUnitTest   # rules, engine and Robolectric UI tests
./gradlew assembleRelease     # app/build/outputs/apk/release/app-release.apk
```

The release build is signed with the debug key so it installs directly; configure your own
signing key before publishing it to a store.
