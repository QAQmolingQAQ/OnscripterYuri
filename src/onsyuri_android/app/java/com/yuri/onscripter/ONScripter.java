package com.yuri.onscripter;

import static com.yuri.onscripter.MainActivity.SHAREDPREF_GAMECONFIG;
import static com.yuri.onscripter.MainActivity.SHAREDPREF_GAMEURI;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;
import androidx.documentfile.provider.DocumentFile;

import org.libsdl.app.SDLActivity;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Objects;

public class ONScripter extends SDLActivity {
    private static final float STICK_THRESHOLD = 0.55f;
    private static final float TRIGGER_THRESHOLD = 0.5f;

    private ArrayList<String> m_onsargs;
    // use this to judge whether to use saf
    private DocumentFile m_onsbase;
    // smb games are read through a virtual path, no full download needed
    private SmbVirtualFs m_smbVirtual;
    private boolean m_ignorecutout;

    // gamepad -> keyboard mapping
    private GamepadMapping m_gamepad;
    private final boolean[] m_buttonDown = new boolean[GamepadMapping.BUTTON_KEYCODES.length];
    private final boolean[] m_stickDown = new boolean[4]; // up, down, left, right
    private final boolean[] m_triggerDown = new boolean[2]; // lt, rt

    // for onsyuri c code
    private native int nativeInitJavaCallbacks();

    @SuppressWarnings("UnnecessaryLocalVariable")
    public int getFD(byte[] pathbyte, int mode) {
        String path = new String(pathbyte, StandardCharsets.UTF_8);
        if (m_smbVirtual != null && m_smbVirtual.isVirtual(path)) {
            return m_smbVirtual.openFd(path, mode);
        }
        if (m_onsbase==null) return -1;
        String safmode = mode==0 ? "r" : "w";
        int fd = SafFile.getFdSaf(this, m_onsbase, path, safmode);
        // Log.i("## onsyuri_android", String.format("getFD path=%s, mode=%d, fd=%d", new String(path), mode, fd));
        return fd;
    }

    public int mkdir(byte[] pathbyte) {
        String path = new String(pathbyte, StandardCharsets.UTF_8);
        if (m_smbVirtual != null && m_smbVirtual.isVirtual(path)) {
            return m_smbVirtual.mkdir(path);
        }
        if (m_onsbase==null) return -1;
        DocumentFile doc = SafFile.mkdirsSaf(m_onsbase, path);
        if (doc==null) return  -1;
        else  return 0;
    }

    public void playVideo(byte[] pathbyte) {
        String path = new String(pathbyte, StandardCharsets.UTF_8);
        try {
            Uri uri;
            path = path.replace('\\', '/');
            if(m_onsbase==null)  {
                // scoped storage, content://com.yuri.onscripter.fileProvider/external_file_path/
                uri = FileProvider.getUriForFile(this,
                        Objects.requireNonNull(getClass().getPackage()).getName() +".fileProvider",
                        new File(path));
            }
            else uri = Uri.parse(m_onsbase.getUri() + "%2F" + Uri.encode(path));
            Log.i("## onsyuri_android", "playVideo " + uri.toString());
            invokeSystemPlayer(uri);

        } catch (Exception e) {
            Log.e("## onsyuri_android", "playVideo error:  " + e.getClass().getName() + " "+ e.getMessage());
        }
    }

    private void invokeSystemPlayer(Uri uri){
        AlertDialog.Builder builder = new AlertDialog.Builder(ONScripter.this);
        builder.setTitle("Playing game video");
        StringBuilder message = new StringBuilder();
        message.append("playVideo: ").append(uri.toString()).append("\n");
        message.append("Please select a video app to play ! ");
        AlertDialog.Builder builder1 = builder.setMessage(message);
        builder.setCancelable(false);
        builder.setPositiveButton("Yes", (dialog, which) -> {
            dialog.dismiss();
            Intent intent = new Intent("android.intent.action.VIEW");
            intent.setDataAndType(uri, "video/*");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(intent, -1);
        });
        builder.setNegativeButton("No", (dialog, which) -> dialog.dismiss());
        runOnUiThread(() -> {
            AlertDialog dialog = builder.create();
            dialog.show();
        });
    }

    // override sdl functions
    static {
        System.loadLibrary("lua");
        System.loadLibrary("jpeg");
        System.loadLibrary("bz2");
        System.loadLibrary("onsyuri");
    }

    @Override
    protected String[] getLibraries() {
        return new String[] {
                "SDL2",
                "SDL2_image",
                "SDL2_mixer",
                "SDL2_ttf",
                "onsyuri"
        };
    }

    @Override
    protected String[] getArguments() {
        return m_onsargs.toArray(new String[0]);
    }

    // override activity functions
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent intent = getIntent();

        m_onsargs = intent.getStringArrayListExtra(SHAREDPREF_GAMECONFIG);
        m_ignorecutout = intent.getBooleanExtra("ignorecutout", true);
        String uristr = intent.getStringExtra(SHAREDPREF_GAMEURI);
        if(uristr!=null) {
            Uri uri = Uri.parse(uristr);
            m_onsbase = DocumentFile.fromTreeUri(this, uri);
        }
        else {
            m_onsbase = null;
        }

        String smbtoken = intent.getStringExtra(MainActivity.SHAREDPREF_SMB_TOKEN);
        if (smbtoken != null) {
            m_smbVirtual = new SmbVirtualFs(this, smbtoken,
                    intent.getStringExtra(MainActivity.SHAREDPREF_SMB_HOST),
                    intent.getIntExtra(MainActivity.SHAREDPREF_SMB_PORT, GameSources.DEFAULT_SMB_PORT),
                    intent.getStringExtra(MainActivity.SHAREDPREF_SMB_SHARE),
                    intent.getStringExtra(MainActivity.SHAREDPREF_SMB_BASE),
                    intent.getStringExtra(MainActivity.SHAREDPREF_SMB_USER),
                    intent.getStringExtra(MainActivity.SHAREDPREF_SMB_PASSWORD));
        }

        m_gamepad = GamepadMapping.load(getSharedPreferences(MainActivity.SHAREDPREF_NAME, MODE_PRIVATE));
        nativeInitJavaCallbacks();
        this.fullscreen();
    }

    @Override
    protected void onResume() {
        super.onResume();
        m_gamepad = GamepadMapping.load(getSharedPreferences(MainActivity.SHAREDPREF_NAME, MODE_PRIVATE));
        this.fullscreen();
    }

    @Override
    protected void onPause() {
        releaseAllGamepadKeys();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (m_smbVirtual != null) {
            m_smbVirtual.close();
            m_smbVirtual = null;
        }
        super.onDestroy();
    }

    // ---------------------------------------------------------- gamepad input

    private boolean isGamepadEvent(KeyEvent event) {
        if (GamepadMapping.isGamepadDevice(event.getDeviceId())) return true;
        int source = event.getSource();
        return (source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                || (source & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int buttonIndex = GamepadMapping.findButtonIndex(event.getKeyCode());
        if (buttonIndex >= 0 && m_gamepad != null && isGamepadEvent(event)) {
            int mapped = m_gamepad.getMappedKeycode(buttonIndex);
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) {
                    m_buttonDown[buttonIndex] = true;
                    if (mapped != GamepadMapping.ACTION_NONE) onNativeKeyDown(mapped);
                }
            } else if (event.getAction() == KeyEvent.ACTION_UP) {
                m_buttonDown[buttonIndex] = false;
                if (mapped != GamepadMapping.ACTION_NONE) onNativeKeyUp(mapped);
            }
            return true; // consume, so that the native fixed mapping is not used
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        int sources = event.getSource();
        if ((sources & InputDevice.SOURCE_CLASS_JOYSTICK) == 0) {
            return super.dispatchGenericMotionEvent(event);
        }
        if (m_gamepad == null) return super.dispatchGenericMotionEvent(event);
        if (event.getAction() == MotionEvent.ACTION_MOVE) {
            if (m_gamepad.isLeftStickDpad()) {
                float x = event.getAxisValue(MotionEvent.AXIS_X);
                float y = event.getAxisValue(MotionEvent.AXIS_Y);
                updateStickAxis(0, y < -STICK_THRESHOLD, KeyEvent.KEYCODE_DPAD_UP);
                updateStickAxis(1, y > STICK_THRESHOLD, KeyEvent.KEYCODE_DPAD_DOWN);
                updateStickAxis(2, x < -STICK_THRESHOLD, KeyEvent.KEYCODE_DPAD_LEFT);
                updateStickAxis(3, x > STICK_THRESHOLD, KeyEvent.KEYCODE_DPAD_RIGHT);
            }
            float lt = Math.max(event.getAxisValue(MotionEvent.AXIS_LTRIGGER),
                    event.getAxisValue(MotionEvent.AXIS_BRAKE));
            float rt = Math.max(event.getAxisValue(MotionEvent.AXIS_RTRIGGER),
                    Math.max(event.getAxisValue(MotionEvent.AXIS_GAS), event.getAxisValue(MotionEvent.AXIS_Z)));
            updateTriggerAxis(0, lt > TRIGGER_THRESHOLD);
            updateTriggerAxis(1, rt > TRIGGER_THRESHOLD);
        }
        return true; // consume joystick axes, the app handles them itself
    }

    private void updateStickAxis(int index, boolean pressed, int keycode) {
        if (m_stickDown[index] == pressed) return;
        m_stickDown[index] = pressed;
        if (pressed) onNativeKeyDown(keycode);
        else onNativeKeyUp(keycode);
    }

    private void updateTriggerAxis(int index, boolean pressed) {
        if (m_triggerDown[index] == pressed) return;
        m_triggerDown[index] = pressed;
        // LT / RT are the button indexes 6 / 7
        int buttonIndex = index == 0 ? 6 : 7;
        int mapped = m_gamepad.getMappedKeycode(buttonIndex);
        if (mapped == GamepadMapping.ACTION_NONE) return;
        if (pressed) onNativeKeyDown(mapped);
        else onNativeKeyUp(mapped);
    }

    private void releaseAllGamepadKeys() {
        if (m_gamepad == null) return;
        for (int i = 0; i < m_buttonDown.length; i++) {
            if (m_buttonDown[i]) {
                int mapped = m_gamepad.getMappedKeycode(i);
                if (mapped != GamepadMapping.ACTION_NONE) onNativeKeyUp(mapped);
                m_buttonDown[i] = false;
            }
        }
        int[] stickKeys = {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT
        };
        for (int i = 0; i < m_stickDown.length; i++) {
            if (m_stickDown[i]) {
                onNativeKeyUp(stickKeys[i]);
                m_stickDown[i] = false;
            }
        }
        for (int i = 0; i < m_triggerDown.length; i++) {
            if (m_triggerDown[i]) {
                int mapped = m_gamepad.getMappedKeycode(i == 0 ? 6 : 7);
                if (mapped != GamepadMapping.ACTION_NONE) onNativeKeyUp(mapped);
                m_triggerDown[i] = false;
            }
        }
    }


    public void onWindowFocusChanged (boolean hasFocus) {
        if(hasFocus) this.fullscreen();
    }

    private void fullscreen() {

        if (m_ignorecutout && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) { // use cutout
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }

        View decorView = getWindow().getDecorView();
        int uiOptions = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN ;
        decorView.setSystemUiVisibility(uiOptions);
        // hide title for SDL
        try {
            Objects.requireNonNull(this.getSupportActionBar()).hide();
        }
        catch (NullPointerException ignored){}
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
    }
}
