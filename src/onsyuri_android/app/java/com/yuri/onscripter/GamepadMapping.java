/**
 * Android gamepad button -> keyboard key mapping, used by the SDL activity.
 * Configurable from the main settings screen.
 *
 * v0.7.8, for OnscripterYuri android
 */
package com.yuri.onscripter;

import android.content.SharedPreferences;
import android.view.InputDevice;
import android.view.KeyEvent;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class GamepadMapping {

    public static final String PREF_KEY = "gamepadmap";
    public static final int ACTION_NONE = 0;

    // controller buttons, in the order they are shown in the settings dialog
    public static final String[] BUTTON_NAMES = {
            "A", "B", "X", "Y",
            "LB", "RB", "LT", "RT",
            "L3 (left stick click)", "R3 (right stick click)",
            "START", "SELECT / BACK", "GUIDE",
            "DPAD UP", "DPAD DOWN", "DPAD LEFT", "DPAD RIGHT"
    };

    public static final int[] BUTTON_KEYCODES = {
            KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_BUTTON_X,
            KeyEvent.KEYCODE_BUTTON_Y,
            KeyEvent.KEYCODE_BUTTON_L1,
            KeyEvent.KEYCODE_BUTTON_R1,
            KeyEvent.KEYCODE_BUTTON_L2,
            KeyEvent.KEYCODE_BUTTON_R2,
            KeyEvent.KEYCODE_BUTTON_THUMBL,
            KeyEvent.KEYCODE_BUTTON_THUMBR,
            KeyEvent.KEYCODE_BUTTON_START,
            KeyEvent.KEYCODE_BUTTON_SELECT,
            KeyEvent.KEYCODE_BUTTON_MODE,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT
    };

    public static final String[] ACTION_NAMES = {
            "none",
            "ENTER (confirm)",
            "ESCAPE (back / menu)",
            "SPACE",
            "CTRL RIGHT (skip)",
            "CTRL LEFT (skip)",
            "A (auto play)",
            "O",
            "S",
            "0",
            "F10",
            "UP",
            "DOWN",
            "LEFT",
            "RIGHT",
            "PAGE UP",
            "PAGE DOWN",
            "VOLUME UP",
            "VOLUME DOWN",
            "SHIFT RIGHT"
    };

    public static final int[] ACTION_KEYCODES = {
            0,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_ESCAPE,
            KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_CTRL_RIGHT,
            KeyEvent.KEYCODE_CTRL_LEFT,
            KeyEvent.KEYCODE_A,
            KeyEvent.KEYCODE_O,
            KeyEvent.KEYCODE_S,
            KeyEvent.KEYCODE_0,
            KeyEvent.KEYCODE_F10,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_PAGE_UP,
            KeyEvent.KEYCODE_PAGE_DOWN,
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_SHIFT_RIGHT
    };

    private static final int[] DEFAULT_ACTION_INDEX = {
            1,  // A -> ENTER
            2,  // B -> ESCAPE
            4,  // X -> CTRL RIGHT
            3,  // Y -> SPACE
            7,  // LB -> O
            8,  // RB -> S
            0,  // LT -> none
            0,  // RT -> none
            4,  // L3 -> CTRL RIGHT
            1,  // R3 -> ENTER
            6,  // START -> A
            9,  // SELECT -> 0
            10, // GUIDE -> F10
            11, // DPAD UP
            12, // DPAD DOWN
            13, // DPAD LEFT
            14  // DPAD RIGHT
    };

    private final int[] m_actions = new int[BUTTON_KEYCODES.length];
    private boolean m_leftStickDpad = true;

    public GamepadMapping() {
        resetDefaults();
    }

    public void resetDefaults() {
        System.arraycopy(DEFAULT_ACTION_INDEX, 0, m_actions, 0, m_actions.length);
        m_leftStickDpad = true;
    }

    public boolean isLeftStickDpad() { return m_leftStickDpad; }
    public void setLeftStickDpad(boolean enabled) { m_leftStickDpad = enabled; }

    public int getButtonCount() { return BUTTON_KEYCODES.length; }
    public String getButtonName(int index) { return BUTTON_NAMES[index]; }
    public String getActionName(int index) { return ACTION_NAMES[index]; }
    public String getButtonActionName(int buttonIndex) {
        int a = m_actions[buttonIndex];
        return a >= 0 && a < ACTION_NAMES.length ? ACTION_NAMES[a] : ACTION_NAMES[0];
    }
    public int getButtonActionIndex(int buttonIndex) { return m_actions[buttonIndex]; }
    public void setButtonActionIndex(int buttonIndex, int actionIndex) {
        if (buttonIndex >= 0 && buttonIndex < m_actions.length &&
                actionIndex >= 0 && actionIndex < ACTION_NAMES.length) {
            m_actions[buttonIndex] = actionIndex;
        }
    }

    /** @return index in BUTTON_KEYCODES, or -1 when the key is not a gamepad button */
    public static int findButtonIndex(int keyCode) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            // many controllers send BACK for the select button
            for (int i = 0; i < BUTTON_KEYCODES.length; i++) {
                if (BUTTON_KEYCODES[i] == KeyEvent.KEYCODE_BUTTON_SELECT) return i;
            }
        }
        for (int i = 0; i < BUTTON_KEYCODES.length; i++) {
            if (BUTTON_KEYCODES[i] == keyCode) return i;
        }
        return -1;
    }

    /** @return android keycode mapped to this button, or ACTION_NONE */
    public int getMappedKeycode(int buttonIndex) {
        if (buttonIndex < 0 || buttonIndex >= m_actions.length) return ACTION_NONE;
        return ACTION_KEYCODES[m_actions[buttonIndex]];
    }

    public int getMappedKeycodeByKeyCode(int keyCode) {
        return getMappedKeycode(findButtonIndex(keyCode));
    }

    public static boolean isGamepadDevice(int deviceId) {
        if (deviceId < 0) return false;
        InputDevice device = InputDevice.getDevice(deviceId);
        if (device == null) return false;
        int sources = device.getSources();
        return (sources & InputDevice.SOURCE_CLASS_JOYSTICK) != 0
                || (sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (sources & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD;
    }

    public static GamepadMapping load(SharedPreferences prefs) {
        GamepadMapping map = new GamepadMapping();
        String raw = prefs.getString(PREF_KEY, null);
        if (raw == null) return map;
        try {
            JSONObject o = new JSONObject(raw);
            JSONArray arr = o.optJSONArray("actions");
            if (arr != null) {
                for (int i = 0; i < map.m_actions.length && i < arr.length(); i++) {
                    int v = arr.optInt(i, map.m_actions[i]);
                    if (v >= 0 && v < ACTION_NAMES.length) map.m_actions[i] = v;
                }
            }
            map.m_leftStickDpad = o.optBoolean("leftstick", true);
        } catch (JSONException ignored) {}
        return map;
    }

    public void save(SharedPreferences prefs) {
        JSONObject o = new JSONObject();
        try {
            JSONArray arr = new JSONArray();
            for (int action : m_actions) arr.put(action);
            o.put("actions", arr);
            o.put("leftstick", m_leftStickDpad);
        } catch (JSONException ignored) {}
        prefs.edit().putString(PREF_KEY, o.toString()).apply();
    }
}
