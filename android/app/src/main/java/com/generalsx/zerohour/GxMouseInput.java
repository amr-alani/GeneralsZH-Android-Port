package com.generalsx.zerohour;

import android.content.Context;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.Locale;

import org.libsdl.app.SDL;
import org.libsdl.app.SDLActivity;

// GeneralsX @bugfix Android port 30/10/2026 A Bluetooth mouse needed a long press before a
// click registered (Galaxy Z Fold 8, One UI). SDL's own glue (SDLSurface.onTouch,
// SDLGenericMotionListener) trusts three things that do not hold on every device:
//
//   1. a mouse always reports TOOL_TYPE_MOUSE -- some report TOOL_TYPE_FINGER/UNKNOWN with a
//      mouse source, and those clicks then went down the touch layer, where a click is a tap
//      and a held press is a right click;
//   2. ACTION_DOWN carries the pressed button in getButtonState() -- some devices send it with
//      0 and report the button only in a separate ACTION_BUTTON_PRESS, which SDL ignores, so
//      the press was translated as "button 0" and dropped;
//   3. the right button arrives as a button -- with One UI's "secondary button: go back" it
//      arrives as KEYCODE_BACK from a mouse source, which SDL swallows on purpose.
//
// The patched SDL glue (android/patches/sdl-android-mouse-buttons.patch) routes every mouse
// event through here. Buttons are tracked here and handed to SDL one changed button at a time,
// so SDL's own button diff (Android_OnMouse) always sees exactly one new or released button.
//
// Every mouse event is also written to mouse-input.log (shared with the other logs from the
// launcher's log screen), so a device that still misbehaves can be diagnosed from one report.
public final class GxMouseInput {

    public static final String LOG_NAME = "mouse-input.log";
    private static final int MAX_LOG_LINES = 4000;

    private static final int[] BUTTON_BITS = {
        MotionEvent.BUTTON_PRIMARY,
        MotionEvent.BUTTON_SECONDARY,
        MotionEvent.BUTTON_TERTIARY,
        MotionEvent.BUTTON_BACK,
        MotionEvent.BUTTON_FORWARD,
    };

    private static int sButtons = 0;
    private static boolean sSawRealSecondary = false;
    private static boolean sRightFromBackKey = false;
    private static float sLastX = 0.0f;
    private static float sLastY = 0.0f;
    private static boolean sLastRelative = false;

    private static PrintWriter sLog;
    private static boolean sLogTried = false;
    private static int sLogLines = 0;

    private GxMouseInput() {
    }

    /** True when pointer {@code i} of {@code event} comes from a mouse, whatever tool type it claims. */
    public static boolean isMouse(MotionEvent event, int i) {
        int tool = event.getToolType(i);
        if (tool == MotionEvent.TOOL_TYPE_MOUSE) {
            return true;
        }
        if (tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER) {
            return false;
        }
        // A finger on the screen itself is never a mouse, even on a device that also has one.
        return event.isFromSource(InputDevice.SOURCE_MOUSE)
            && !event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN);
    }

    /** A mouse event from SDLSurface.onTouch (presses, releases, drags). */
    public static void onTouchMouse(MotionEvent event, int i, float x, float y, boolean relative) {
        int action = event.getActionMasked();
        int state = event.getButtonState();
        log("touch", event, i, x, y);
        remember(x, y, relative);

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                // Some devices report the button only in a later ACTION_BUTTON_PRESS (or never):
                // a press with no button is the primary one.
                if (state == 0) {
                    state = MotionEvent.BUTTON_PRIMARY;
                }
                move(MotionEvent.ACTION_MOVE);
                setButtons(sButtons | state, false);
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                move(MotionEvent.ACTION_MOVE);
                // Android reports what is still held; anything else was released. A right button
                // that came from the Back key is not in Android's state, so keep it as it is.
                setButtons(mergeBackKeyButton(state), false);
                break;

            case MotionEvent.ACTION_BUTTON_PRESS:
            case MotionEvent.ACTION_BUTTON_RELEASE:
                setButtons(mergeBackKeyButton(state), false);
                break;

            case MotionEvent.ACTION_MOVE:
                move(MotionEvent.ACTION_MOVE);
                break;

            default:
                SDLActivity.onNativeMouse(state, action, x, y, relative);
                break;
        }
    }

    /**
     * A mouse event from SDLGenericMotionListener.onGenericMotion. Returns true when handled here;
     * false leaves it to SDL (scrolling).
     */
    public static boolean onGenericMouse(MotionEvent event, int i, float x, float y, boolean relative) {
        int action = event.getActionMasked();
        if (action != MotionEvent.ACTION_HOVER_MOVE) {
            // Hover moves are too many to log usefully; everything else is a press, release,
            // scroll or enter/exit and worth seeing.
            log("generic", event, i, x, y);
        }
        switch (action) {
            case MotionEvent.ACTION_BUTTON_PRESS:
            case MotionEvent.ACTION_BUTTON_RELEASE:
                remember(x, y, relative);
                move(MotionEvent.ACTION_HOVER_MOVE);
                setButtons(mergeBackKeyButton(event.getButtonState()), false);
                return true;

            case MotionEvent.ACTION_HOVER_MOVE:
                remember(x, y, relative);
                SDLActivity.onNativeMouse(0, action, x, y, relative);
                return true;

            default:
                return false;
        }
    }

    /**
     * A key from a mouse source. One UI can turn the right button into KEYCODE_BACK; while this
     * mouse has never sent a real right button, treat that Back as the right button. Returns true
     * when the key was used here.
     */
    public static boolean onMouseKey(KeyEvent event) {
        log(event);
        if (event.getKeyCode() != KeyEvent.KEYCODE_BACK) {
            return false;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN && !sSawRealSecondary) {
            if (event.getRepeatCount() == 0) {
                sRightFromBackKey = true;
                move(MotionEvent.ACTION_HOVER_MOVE);
                setButtons(sButtons | MotionEvent.BUTTON_SECONDARY, true);
            }
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_UP && sRightFromBackKey) {
            sRightFromBackKey = false;
            move(MotionEvent.ACTION_HOVER_MOVE);
            setButtons(sButtons & ~MotionEvent.BUTTON_SECONDARY, true);
            return true;
        }
        return false;
    }

    // While the right button is being simulated from the Back key, Android's own button state
    // does not include it; keep it held until the Back key is released.
    private static int mergeBackKeyButton(int state) {
        if (sRightFromBackKey) {
            state |= MotionEvent.BUTTON_SECONDARY;
        }
        return state;
    }

    private static void remember(float x, float y, boolean relative) {
        sLastX = x;
        sLastY = y;
        sLastRelative = relative;
    }

    private static void move(int action) {
        SDLActivity.onNativeMouse(sButtons, action, sLastX, sLastY, sLastRelative);
    }

    // Hand SDL one changed button per call, presses and releases alike, so its diff against its
    // own last state names exactly that button.
    private static void setButtons(int newState, boolean fromBackKey) {
        if (!fromBackKey && (newState & MotionEvent.BUTTON_SECONDARY) != 0
                && (sButtons & MotionEvent.BUTTON_SECONDARY) == 0) {
            sSawRealSecondary = true;
        }
        for (int bit : BUTTON_BITS) {
            boolean was = (sButtons & bit) != 0;
            boolean now = (newState & bit) != 0;
            if (was == now) {
                continue;
            }
            sButtons = now ? (sButtons | bit) : (sButtons & ~bit);
            SDLActivity.onNativeMouse(sButtons, now ? MotionEvent.ACTION_DOWN : MotionEvent.ACTION_UP,
                sLastX, sLastY, sLastRelative);
            logLine(String.format(Locale.US, "  -> SDL button %s %s", buttonName(bit), now ? "DOWN" : "UP"));
        }
    }

    private static String buttonName(int bit) {
        switch (bit) {
            case MotionEvent.BUTTON_PRIMARY: return "LEFT";
            case MotionEvent.BUTTON_SECONDARY: return "RIGHT";
            case MotionEvent.BUTTON_TERTIARY: return "MIDDLE";
            case MotionEvent.BUTTON_BACK: return "X2(back)";
            case MotionEvent.BUTTON_FORWARD: return "X1(forward)";
            default: return Integer.toString(bit);
        }
    }

    private static void log(String path, MotionEvent e, int i, float x, float y) {
        logLine(String.format(Locale.US, "%s %s src=0x%x tool=%d buttons=0x%x x=%.0f y=%.0f",
            path, MotionEvent.actionToString(e.getActionMasked()), e.getSource(), e.getToolType(i),
            e.getButtonState(), x, y));
    }

    private static void log(KeyEvent e) {
        logLine(String.format(Locale.US, "key %s %s src=0x%x repeat=%d",
            KeyEvent.keyCodeToString(e.getKeyCode()),
            e.getAction() == KeyEvent.ACTION_DOWN ? "DOWN" : (e.getAction() == KeyEvent.ACTION_UP ? "UP" : "OTHER"),
            e.getSource(), e.getRepeatCount()));
    }

    private static void logLine(String line) {
        try {
            if (!sLogTried) {
                sLogTried = true;
                Context context = SDL.getContext();
                File dir = context != null ? context.getExternalFilesDir(null) : null;
                if (dir != null) {
                    sLog = new PrintWriter(new FileWriter(new File(dir, LOG_NAME), false));
                    sLog.println("mouse input log (one game session)");
                }
            }
            if (sLog == null || sLogLines >= MAX_LOG_LINES) {
                return;
            }
            sLog.println(SystemClock.uptimeMillis() + " " + line);
            sLog.flush();
            if (++sLogLines == MAX_LOG_LINES) {
                sLog.println("(log full)");
                sLog.flush();
            }
        } catch (Throwable ignored) {
            // A log must never break input.
            sLog = null;
        }
    }
}
