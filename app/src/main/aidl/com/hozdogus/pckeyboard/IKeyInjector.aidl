package com.hozdogus.pckeyboard;

// Runs inside Shizuku with the same rights as ADB, so it can send key presses
// to whatever app is on screen.
interface IKeyInjector {
    // Shizuku calls this transaction code to shut the service down.
    void destroy() = 16777114;

    boolean injectKey(int action, int keyCode, int metaState, long downTime, int repeat) = 1;
}
