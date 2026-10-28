package com.hozdogus.pckeyboard;

// Runs inside Shizuku with the same rights as ADB, so it can send key presses
// to whatever app is on screen.
interface IKeyInjector {
    // Shizuku calls this transaction code to shut the service down.
    void destroy() = 16777114;

    boolean injectKey(int action, int keyCode, int metaState, long downTime, int repeat) = 1;

    // Same, but lets the press come from a gamepad instead of a keyboard.
    boolean injectKeyFrom(int action, int keyCode, int metaState, long downTime, int repeat, int source) = 2;

    // Analog stick: stick 0 = left, 1 = right; x and y from -1 to 1.
    boolean injectStick(int stick, float x, float y) = 3;
}
