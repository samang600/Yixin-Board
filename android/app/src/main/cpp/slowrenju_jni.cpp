// JNI bridge between the app (io.github.yixinboard.engine.SlowRenju) and the SlowRenju engine.
// Copyright (C) 2026 Yixin Board for Android contributors. GPL-3.0-or-later (see slowrenju/LICENSE).
#include <jni.h>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <mutex>
#include "slowrenju/Headers/game.h"

// Referenced by the engine's ANDROID build (originally defined in its own JNI file).
int thinking = 0;
int thinktime = 0;

extern int board[N][N];
extern int bmove;
extern char curstatus[];
void InitHash();

static std::mutex gSearchLock;
static int gLastRule = -1;

extern "C" {

/**
 * Searches the position given by [moves] (indices y*15+x, black first).
 * rule: 0 freestyle, 1 standard (exactly five), 2 renju. nodeLimit 0 = unlimited (stop with nativeStop).
 * Returns the chosen move as y*15+x.
 */
JNIEXPORT jint JNICALL
Java_io_github_yixinboard_engine_SlowRenju_nativeThink(JNIEnv *env, jclass, jintArray moves, jint rule, jlong nodeLimit) {
    std::lock_guard<std::mutex> guard(gSearchLock);
    jsize n = env->GetArrayLength(moves);
    jint *m = env->GetIntArrayElements(moves, nullptr);
    memset(board, 0, sizeof(board));
    for (jsize k = 0; k < n; k++) {
        int idx = m[k];
        if (idx >= 0 && idx < N * N) board[idx % N][idx / N] = (k % 2 == 0) ? 1 : -1;
    }
    env->ReleaseIntArrayElements(moves, m, JNI_ABORT);

    bmove = n;
    moven = n;
    fflag = (rule == 2) ? 1 : 0;
    nosix = (rule == 1) ? 1 : 0;
    compend = 0;
    comphalfend = 0;
    nodelimit = nodeLimit;
    if (gLastRule != rule) {
        InitHash();
        revaluew();
        gLastRule = rule;
    }
    ts = clock();
    timee = 2147483647;
    timel = 2147483647;
    thinking = 1;
    int ret = rootsearch(24, 60, 1, 1);
    thinking = 0;
    return ret;
}

/** Asks a running search to stop; it then returns its best move so far. */
JNIEXPORT void JNICALL
Java_io_github_yixinboard_engine_SlowRenju_nativeStop(JNIEnv *, jclass) {
    compend = 1;
}

/** {nodes, depth, eval} of the current or last search. */
JNIEXPORT jlongArray JNICALL
Java_io_github_yixinboard_engine_SlowRenju_nativeStatus(JNIEnv *env, jclass) {
    char buf[10000];
    strncpy(buf, curstatus, sizeof(buf) - 1);
    buf[sizeof(buf) - 1] = 0;
    jlong out[3] = {(jlong) countx, 0, 0};
    // Format: "move;move;<variation>;depth;eval" - read the last two fields.
    char *last = strrchr(buf, ';');
    if (last) {
        out[2] = atol(last + 1);
        *last = 0;
        char *prev = strrchr(buf, ';');
        if (prev) out[1] = atol(prev + 1);
    }
    jlongArray arr = env->NewLongArray(3);
    env->SetLongArrayRegion(arr, 0, 3, out);
    return arr;
}

}
