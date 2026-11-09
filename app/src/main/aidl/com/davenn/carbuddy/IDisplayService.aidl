package com.davenn.carbuddy;

import android.view.Surface;

// Runs inside Shizuku's shell-privileged process (see DisplayService.kt).
// Everything here needs permissions a normal app cannot hold: creating a
// trusted virtual display, launching other apps onto it, and injecting input.
interface IDisplayService {

    // Reserved by Shizuku: called when the service is being torn down.
    void destroy() = 16777114;

    // Creates a virtual display that renders straight into `surface` (the car
    // screen) and returns its display id, or -1 on failure. Any previous
    // display is released first.
    int createDisplay(in Surface surface, int width, int height, int dpi) = 1;

    void releaseDisplay() = 2;

    // Starts `component` ("pkg/.Activity") on the current display.
    // Returns the output of `am start`, which includes any error text.
    String launch(String component) = 3;

    // One touch event on the current display, in display pixels.
    // action is a MotionEvent action (DOWN, MOVE, UP).
    void touch(int action, float x, float y, long downTime) = 4;

    // A key press (down + up) on the current display, e.g. KEYCODE_BACK.
    void key(int keyCode) = 5;

    // Text describing the last failure, for showing on the phone or car.
    String lastError() = 6;
}
