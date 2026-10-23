package com.psprecomp.vcs;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends Activity implements SurfaceHolder.Callback {
    static { System.loadLibrary("vcs_android"); }
    private static native String nativeStatus();
    private static native void nativeSetSurface(Surface surface);
    private static native void nativeSetDisplaySize(int width, int height);
    private static native void nativeSetInput(int buttons, int analogX, int analogY,
                                              boolean accelerate, boolean brake);
    private static native void nativeStartGame(String gameRoot, String appDataDirectory);
    private static native void nativeStopGame();
    public static native boolean nativeIsPlayerInVehicle();
    public static native void nativeSendCheat(int cheatId);
    private static native String nativeSaveState(boolean save, String file);
    private static native void nativeAddCameraMotion(float dx, float dy);
    private static native void nativeSetCameraStick(int x, int y);

    // Touch camera: screen pixels to the mouse counts the camera curve expects,
    // scaled by density so a drag of the same length turns the same on any panel.
    private static final float TOUCH_CAMERA_COUNTS_PER_DP = 4.0f;
    private static final String[] CAMERA_SENSITIVITY_LABELS = {"Низкая", "Средняя", "Высокая", "Очень высокая", "Максимальная"};
    // Drag distance multipliers; the sticks' full-speed point moves inwards
    // with the same setting (see stickCameraValue).
    private static final float[] CAMERA_SENSITIVITY_SCALES = {0.5f, 1f, 1.6f, 2.5f, 4f};
    private int cameraSensitivity = 2;
    // Camera stick from the touch right stick and from a controller, -127..127
    // each; whichever is deflected further is sent.
    private int touchCameraX, touchCameraY, padCameraX, padCameraY;
    private int sentCameraX, sentCameraY;

    private FrameLayout rootContainer;
    private LinearLayout mainMenuOverlay;
    private boolean isRussian = true;
    private boolean gameLaunchAllowed = false;
    private static final int STATE_SLOTS = 5;
    private boolean stateBusy;

    private static final String CONFIG_FILE = "VCSNative.ini";

    private SurfaceView surfaceView;
    private TouchControlsView controls;
    private TextView statusView;
    private boolean surfaceReady, startRequested, permissionPrompted;
    private long gameStartAt;
    private int panelWidth, panelHeight;

    // Touch and physical-controller state, merged in pushInput(): either can
    // drive the game, and a pad does not have to fight the touch overlay.
    private int touchButtons, touchAnalogX = 128, touchAnalogY = 128;
    private int padButtons, padHatButtons;
    private float padStickX, padStickY;
    private static final float PAD_DEAD_ZONE = 0.18f;
    // Hardware keyboard and mouse, bound like the desktop host (display_window.cpp).
    private int keyboardButtons, mouseButtons;
    private boolean keyW, keyA, keyS, keyD, keyWalk;
    private final Handler handler = new Handler();
    private final Runnable statusPoll = new Runnable() {
        @Override public void run() {
            if (statusView != null) {
                String status = nativeStatus();
                statusView.setText(status);
                boolean running = status.startsWith("Игра запущена.") || status.startsWith("Juego en marcha.");
                statusView.setVisibility(running && SystemClock.uptimeMillis() - gameStartAt > 6000
                    ? View.GONE : View.VISIBLE);
            }
            handler.postDelayed(this, 1000);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // Draw under the camera cutout too; the controls steer clear of it themselves.
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        hideSystemBars();
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);

        // The panel's real size, landscape, for InternalResolutionMode=Desktop ("native").
        DisplayMetrics metrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(metrics);
        panelWidth = Math.max(metrics.widthPixels, metrics.heightPixels);
        panelHeight = Math.min(metrics.widthPixels, metrics.heightPixels);
        nativeSetDisplaySize(panelWidth, panelHeight);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        cameraSensitivity = Math.max(0, Math.min(CAMERA_SENSITIVITY_SCALES.length - 1,
            getSharedPreferences("controls", Context.MODE_PRIVATE).getInt("camera_sensitivity", 2)));
        surfaceView = new GameSurface();
        // A mouse is captured on its first click, as on the desktop, and then
        // its motion turns the camera instead of moving a pointer.
        surfaceView.setFocusable(true);
        surfaceView.setFocusableInTouchMode(true);
        surfaceView.setOnCapturedPointerListener((view, event) -> {
            float dx = 0f, dy = 0f;
            for (int h = 0; h < event.getHistorySize(); ++h) {
                dx += event.getHistoricalX(h);
                dy += event.getHistoricalY(h);
            }
            dx += event.getX();
            dy += event.getY();
            if (dx != 0f || dy != 0f) nativeAddCameraMotion(dx, dy);
            handleMouse(event);
            return true;
        });
        surfaceView.getHolder().setFormat(PixelFormat.RGBA_8888);
        surfaceView.getHolder().addCallback(this);
        root.addView(surfaceView, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER));

        controls = new TouchControlsView(this, new TouchControlsView.Listener() {
            @Override public void onInput(int buttons, int analogX, int analogY,
                                          boolean accelerate, boolean brake) {
                touchButtons = buttons;
                touchAnalogX = analogX;
                touchAnalogY = analogY;
                pushInput();
            }
            @Override public void onSettingsRequested() {
                showSettingsDialog();
            }
            @Override public void onSaveStateRequested() {
                showQuickSaveLoadDialog();
            }
            @Override public void onCameraMotion(float dx, float dy) {
                float scale = TOUCH_CAMERA_COUNTS_PER_DP / getResources().getDisplayMetrics().density
                    * CAMERA_SENSITIVITY_SCALES[cameraSensitivity];
                nativeAddCameraMotion(dx * scale, dy * scale);
            }
            @Override public void onCameraStick(float x, float y) {
                touchCameraX = stickCameraValue(x);
                touchCameraY = stickCameraValue(y);
                pushCamera();
            }
            @Override public void onEditModeChanged(boolean editing) {
                if (editing) statusView.setVisibility(View.GONE);
            }
        });
        root.addView(controls, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        statusView = new TextView(this);
        statusView.setTextColor(Color.WHITE);
        statusView.setTextSize(13);
        statusView.setPadding(18, 10, 18, 10);
        statusView.setBackgroundColor(0x99000000);
        statusView.setMaxLines(2);
        FrameLayout.LayoutParams statusParams = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        root.addView(statusView, statusParams);
        rootContainer = root;
        createMainMenu(rootContainer);
        setContentView(root);
        handler.post(statusPoll);
    }

    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    @Override protected void onResume() {
        super.onResume();
        if (startRequested) {
            maybeStartGame();
        }
    }

    @Override public void onBackPressed() {
        if (controls != null && controls.isEditing()) {
            controls.setEditMode(false);
            return;
        }
        if (mainMenuOverlay != null && mainMenuOverlay.getVisibility() == View.VISIBLE) {
            exitApp();
            return;
        }
        super.onBackPressed();
    }

    @Override protected void onPause() {
        // Nothing may stay held while the app is in the background.
        padButtons = padHatButtons = keyboardButtons = mouseButtons = 0;
        padStickX = padStickY = 0f;
        touchCameraX = touchCameraY = padCameraX = padCameraY = 0;
        pushCamera();
        keyW = keyA = keyS = keyD = keyWalk = false;
        if (controls != null) controls.releaseAll();
        super.onPause();
    }

    private void maybeStartGame() {
        if (!gameLaunchAllowed || startRequested || !surfaceReady) return;
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            if (!permissionPrompted) {
                permissionPrompted = true;
                statusView.setText("Предоставьте доступ к файлам, чтобы загрузить игру из Внутренняя память/VCS.");
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
                } catch (Exception ignored) {
                    startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                }
            }
            return;
        }
        File gameRoot = new File(Environment.getExternalStorageDirectory(), "VCS");
        File elf = new File(gameRoot, "PSP_GAME/SYSDIR/EBOOT_DECRYPTED.ELF");
        if (!elf.isFile()) {
            statusView.setText("Игра не найдена в Внутренняя память/VCS/PSP_GAME.");
            return;
        }
        startRequested = true;
        gameStartAt = SystemClock.uptimeMillis();
        applyLowEndDefaultsOnce(); // before the native side reads the INI
        nativeStartGame(gameRoot.getAbsolutePath(), getFilesDir().getAbsolutePath());
    }

    // --- Physical controllers --------------------------------------------------------------

    private void pushInput() {
        int buttons = touchButtons | padButtons | padHatButtons | keyboardButtons | mouseButtons;
        int ax = touchAnalogX, ay = touchAnalogY;
        float magnitude = (float) Math.sqrt(padStickX * padStickX + padStickY * padStickY);
        if (keyW || keyA || keyS || keyD) {
            // WASD drives the analog stick, as on the desktop; Alt walks.
            float kx = (keyD ? 1f : 0f) - (keyA ? 1f : 0f);
            float ky = (keyS ? 1f : 0f) - (keyW ? 1f : 0f);
            float reach = keyWalk ? 60f : 127f;
            float length = (float) Math.sqrt(kx * kx + ky * ky);
            if (length > 0f) {
                ax = Math.round(128f + kx / length * reach);
                ay = Math.round(128f + ky / length * reach);
            }
        } else if (magnitude > PAD_DEAD_ZONE) {
            // Rescale past the dead zone so the edge of it reads as zero, not a jump.
            float scaled = Math.min(1f, (magnitude - PAD_DEAD_ZONE) / (1f - PAD_DEAD_ZONE));
            ax = Math.round(128f + padStickX / magnitude * scaled * 127f);
            ay = Math.round(128f + padStickY / magnitude * scaled * 127f);
        }
        ax = Math.max(0, Math.min(255, ax));
        ay = Math.max(0, Math.min(255, ay));
        nativeSetInput(buttons, ax, ay,
            (buttons & TouchControlsView.PSP_CROSS) != 0, (buttons & TouchControlsView.PSP_SQUARE) != 0);
    }

    private static boolean isController(InputDevice device, int source) {
        int sources = device != null ? device.getSources() : source;
        return (sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
               (sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
    }

    /** Standard Android gamepad keys, mapped by position to the PSP pad. */
    private static int padMask(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_A: return TouchControlsView.PSP_CROSS;
            case KeyEvent.KEYCODE_BUTTON_B: return TouchControlsView.PSP_CIRCLE;
            case KeyEvent.KEYCODE_BUTTON_X: return TouchControlsView.PSP_SQUARE;
            case KeyEvent.KEYCODE_BUTTON_Y: return TouchControlsView.PSP_TRIANGLE;
            case KeyEvent.KEYCODE_BUTTON_L1:
            case KeyEvent.KEYCODE_BUTTON_L2: return TouchControlsView.PSP_L;
            case KeyEvent.KEYCODE_BUTTON_R1:
            case KeyEvent.KEYCODE_BUTTON_R2: return TouchControlsView.PSP_R;
            case KeyEvent.KEYCODE_BUTTON_START: return TouchControlsView.PSP_START;
            case KeyEvent.KEYCODE_BUTTON_SELECT: return TouchControlsView.PSP_SELECT;
            case KeyEvent.KEYCODE_DPAD_UP: return TouchControlsView.PSP_UP;
            case KeyEvent.KEYCODE_DPAD_DOWN: return TouchControlsView.PSP_DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT: return TouchControlsView.PSP_LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return TouchControlsView.PSP_RIGHT;
            default: return 0;
        }
    }

    /** Keyboard bindings from the desktop host (kKeyBindings in display_window.cpp). */
    private static int keyboardMask(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_SPACE: return TouchControlsView.PSP_CROSS;       // sprint / handbrake
            case KeyEvent.KEYCODE_SHIFT_LEFT:
            case KeyEvent.KEYCODE_SHIFT_RIGHT: return TouchControlsView.PSP_SQUARE; // jump / brake
            case KeyEvent.KEYCODE_F:
            case KeyEvent.KEYCODE_ENTER: return TouchControlsView.PSP_TRIANGLE;    // enter / exit vehicle
            case KeyEvent.KEYCODE_Q: return TouchControlsView.PSP_LEFT;            // previous weapon / station
            case KeyEvent.KEYCODE_E: return TouchControlsView.PSP_RIGHT;           // next weapon / station
            case KeyEvent.KEYCODE_H: return TouchControlsView.PSP_L;               // horn
            case KeyEvent.KEYCODE_DPAD_UP: return TouchControlsView.PSP_UP;
            case KeyEvent.KEYCODE_DPAD_DOWN: return TouchControlsView.PSP_DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT: return TouchControlsView.PSP_LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return TouchControlsView.PSP_RIGHT;
            case KeyEvent.KEYCODE_ESCAPE: return TouchControlsView.PSP_START;      // pause
            case KeyEvent.KEYCODE_TAB: return TouchControlsView.PSP_SELECT;
            default: return 0;
        }
    }

    private boolean handleKeyboard(KeyEvent event) {
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        if (event.getAction() != KeyEvent.ACTION_DOWN && event.getAction() != KeyEvent.ACTION_UP) return false;
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_W: keyW = down; break;
            case KeyEvent.KEYCODE_A: keyA = down; break;
            case KeyEvent.KEYCODE_S: keyS = down; break;
            case KeyEvent.KEYCODE_D: keyD = down; break;
            case KeyEvent.KEYCODE_ALT_LEFT:
            case KeyEvent.KEYCODE_ALT_RIGHT: keyWalk = down; break;
            default: {
                int mask = keyboardMask(event.getKeyCode());
                if (mask == 0) return false;
                if (down) keyboardButtons |= mask; else keyboardButtons &= ~mask;
            }
        }
        if (down) controls.hideForController();
        pushInput();
        return true;
    }

    private static boolean isHardwareKeyboard(KeyEvent event) {
        InputDevice device = event.getDevice();
        return device != null && !device.isVirtual() &&
               (event.getSource() & InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD &&
               device.getKeyboardType() == InputDevice.KEYBOARD_TYPE_ALPHABETIC;
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (controls.isEditing()) return super.dispatchKeyEvent(event);
        if (!isController(event.getDevice(), event.getSource()) && isHardwareKeyboard(event) &&
            handleKeyboard(event)) return true;
        int mask = padMask(event.getKeyCode());
        if (mask != 0 && isController(event.getDevice(), event.getSource()) && !controls.isEditing()) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                padButtons |= mask;
                controls.hideForController();
            } else if (event.getAction() == KeyEvent.ACTION_UP) {
                padButtons &= ~mask;
            }
            pushInput();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    /**
     * Right stick to camera. Most pads report it as Z/RZ, some as RX/RY; the
     * larger deflection of the two pairs wins. Up is positive for the game.
     */
    private void updateCameraStick(MotionEvent event) {
        float x = event.getAxisValue(MotionEvent.AXIS_Z), y = event.getAxisValue(MotionEvent.AXIS_RZ);
        float rx = event.getAxisValue(MotionEvent.AXIS_RX), ry = event.getAxisValue(MotionEvent.AXIS_RY);
        if (rx * rx + ry * ry > x * x + y * y) { x = rx; y = ry; }
        float magnitude = (float) Math.sqrt(x * x + y * y);
        int cx = 0, cy = 0;
        if (magnitude > PAD_DEAD_ZONE) {
            float scaled = Math.min(1f, (magnitude - PAD_DEAD_ZONE) / (1f - PAD_DEAD_ZONE));
            cx = stickCameraValue(x / magnitude * scaled);
            cy = stickCameraValue(-y / magnitude * scaled);
            controls.hideForController();
        }
        padCameraX = cx;
        padCameraY = cy;
        pushCamera();
    }

    /**
     * A stick deflection (-1..1) to the camera axis. Higher sensitivity reaches
     * full turning speed with less of the stick's travel.
     */
    private int stickCameraValue(float deflection) {
        float gain = 0.8f + 0.3f * cameraSensitivity; // 0.8 .. 2.0
        return Math.max(-127, Math.min(127, Math.round(deflection * gain * 127f)));
    }

    private void pushCamera() {
        boolean touchWins = touchCameraX * touchCameraX + touchCameraY * touchCameraY >=
            padCameraX * padCameraX + padCameraY * padCameraY;
        int x = touchWins ? touchCameraX : padCameraX;
        int y = touchWins ? touchCameraY : padCameraY;
        if (x == sentCameraX && y == sentCameraY) return;
        sentCameraX = x;
        sentCameraY = y;
        nativeSetCameraStick(x, y);
    }

    /** Mouse buttons follow the desktop host: fire left, aim right, look behind middle. */
    private boolean handleMouse(MotionEvent event) {
        int state = event.getButtonState();
        int buttons = 0;
        if ((state & MotionEvent.BUTTON_PRIMARY) != 0) buttons |= TouchControlsView.PSP_CIRCLE;
        if ((state & MotionEvent.BUTTON_SECONDARY) != 0) buttons |= TouchControlsView.PSP_R;
        if ((state & MotionEvent.BUTTON_TERTIARY) != 0) buttons |= TouchControlsView.PSP_L;
        if (buttons != 0) {
            controls.hideForController();
            if (!surfaceView.hasPointerCapture()) {
                surfaceView.requestFocus();
                surfaceView.requestPointerCapture();
            }
        }
        if (buttons != mouseButtons) {
            mouseButtons = buttons;
            pushInput();
        }
        return true;
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        // A mouse click arrives as a touch; keep it away from the on-screen pad.
        if (event.getPointerCount() > 0 && event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE &&
            !controls.isEditing())
            return handleMouse(event);
        return super.dispatchTouchEvent(event);
    }

    @Override public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if ((event.getSource() & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE &&
            !controls.isEditing()) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_BUTTON_PRESS || action == MotionEvent.ACTION_BUTTON_RELEASE)
                return handleMouse(event);
        }
        if (isController(event.getDevice(), event.getSource()) &&
            event.getActionMasked() == MotionEvent.ACTION_MOVE && !controls.isEditing()) {
            padStickX = event.getAxisValue(MotionEvent.AXIS_X);
            padStickY = event.getAxisValue(MotionEvent.AXIS_Y);
            updateCameraStick(event);
            // Many pads report the D-pad as a hat axis rather than as keys.
            float hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X);
            float hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y);
            int hat = 0;
            if (hatX < -0.5f) hat |= TouchControlsView.PSP_LEFT;
            if (hatX > 0.5f) hat |= TouchControlsView.PSP_RIGHT;
            if (hatY < -0.5f) hat |= TouchControlsView.PSP_UP;
            if (hatY > 0.5f) hat |= TouchControlsView.PSP_DOWN;
            // Analog triggers on pads that send no L2/R2 key events.
            if (event.getAxisValue(MotionEvent.AXIS_LTRIGGER) > 0.5f ||
                event.getAxisValue(MotionEvent.AXIS_BRAKE) > 0.5f) hat |= TouchControlsView.PSP_L;
            if (event.getAxisValue(MotionEvent.AXIS_RTRIGGER) > 0.5f ||
                event.getAxisValue(MotionEvent.AXIS_GAS) > 0.5f) hat |= TouchControlsView.PSP_R;
            padHatButtons = hat;
            if (hat != 0 || Math.abs(padStickX) > PAD_DEAD_ZONE || Math.abs(padStickY) > PAD_DEAD_ZONE)
                controls.hideForController();
            pushInput();
            return true;
        }
        return super.dispatchGenericMotionEvent(event);
    }

    // --- Resolution ------------------------------------------------------------------------

    /** One choice in the resolution dialog: its label and the [Rendering] keys it writes. */
    private static final class ResolutionChoice {
        final String label, mode;
        final int scale;
        ResolutionChoice(String label, String mode, int scale) {
            this.label = label; this.mode = mode; this.scale = scale;
        }
    }

    /** Width the renderer actually uses at this height: widescreen fills the panel's shape. */
    private int widescreenWidth(int height) {
        return panelHeight > 0 ? Math.round(height * (float) panelWidth / panelHeight) : height * 480 / 272;
    }

    private List<ResolutionChoice> resolutionChoices() {
        List<ResolutionChoice> choices = new ArrayList<>();
        // The PSP's own 272 lines, widened to the panel: for low-end phones.
        choices.add(new ResolutionChoice(String.format(Locale.ROOT,
            "Оригинал PSP — %d×272 (для слабых устройств)", widescreenWidth(272)), "Scale", 1));
        // Below HD: twice the PSP's own height, for the smoothest frame rate.
        choices.add(new ResolutionChoice(String.format(Locale.ROOT,
            "Производительность — %d×544 (более плавно)", widescreenWidth(544)), "Scale", 2));
        choices.add(new ResolutionChoice(String.format(Locale.ROOT, "HD — %d×816", widescreenWidth(816)),
            "Scale", 3));
        choices.add(new ResolutionChoice(String.format(Locale.ROOT, "Full HD — %d×1088", widescreenWidth(1088)),
            "Scale", 4));
        choices.add(new ResolutionChoice(String.format(Locale.ROOT,
            "Родное разрешение экрана — %d×%d", panelWidth, panelHeight), "Desktop", 0));
        return choices;
    }

    private boolean isRussian() {
        return getSharedPreferences("settings", MODE_PRIVATE).getString("language", "ru").equals("ru");
    }

    private void toggleLanguage() {
        boolean ru = isRussian();
        getSharedPreferences("settings", MODE_PRIVATE).edit().putString("language", ru ? "en" : "ru").apply();
        isRussian = !ru;
        if (mainMenuOverlay != null && rootContainer != null) {
            rootContainer.removeView(mainMenuOverlay);
            createMainMenu(rootContainer);
        }
        Toast.makeText(this, isRussian ? "Язык: Русский" : "Language: English", Toast.LENGTH_SHORT).show();
    }

    private void createMainMenu(FrameLayout root) {
        isRussian = isRussian();
        mainMenuOverlay = new LinearLayout(this);
        mainMenuOverlay.setOrientation(LinearLayout.VERTICAL);
        mainMenuOverlay.setGravity(Gravity.CENTER);
        mainMenuOverlay.setBackgroundColor(0xFA0D0E15);
        mainMenuOverlay.setPadding(40, 30, 40, 30);

        TextView titleView = new TextView(this);
        titleView.setText("Grand Theft Auto\nVice City Stories");
        titleView.setTextSize(26);
        titleView.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        titleView.setTextColor(0xFFFF5C8A);
        titleView.setGravity(Gravity.CENTER);
        titleView.setPadding(0, 0, 0, 8);
        mainMenuOverlay.addView(titleView);

        TextView subView = new TextView(this);
        subView.setText("VCS Android Beta v1.3.0 (Alexandercorp287 edition)");
        subView.setTextSize(13);
        subView.setTextColor(0xFF00E5FF);
        subView.setGravity(Gravity.CENTER);
        subView.setPadding(0, 0, 0, 24);
        mainMenuOverlay.addView(subView);

        boolean ru = isRussian;
        addButtonToMenu(mainMenuOverlay, ru ? "▶️  Новая игра" : "▶️  New Game", v -> startNewGame());
        addButtonToMenu(mainMenuOverlay, ru ? "📂  Загрузить игру" : "📂  Load Game", v -> showLoadStateDialog());
        addButtonToMenu(mainMenuOverlay, ru ? "⚙️  Настройки" : "⚙️  Settings", v -> showSettingsDialog());
        addButtonToMenu(mainMenuOverlay, ru ? "ℹ️  Авторы" : "ℹ️  Credits", v -> showCreditsDialog());
        addButtonToMenu(mainMenuOverlay, ru ? "❌  Выход" : "❌  Exit", v -> exitApp());

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        root.addView(mainMenuOverlay, params);
        if (controls != null) controls.setVisibility(View.GONE);
    }

    private void addButtonToMenu(LinearLayout parent, String text, View.OnClickListener click) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextSize(16);
        btn.setTextColor(Color.WHITE);
        btn.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setColor(0xFF1E202F);
        gd.setCornerRadius(18f);
        gd.setStroke(2, 0xFF3D4263);
        btn.setBackground(gd);
        btn.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(540, 105);
        lp.setMargins(0, 10, 0, 10);
        parent.addView(btn, lp);
    }

    private void startNewGame() {
        gameLaunchAllowed = true;
        if (mainMenuOverlay != null) mainMenuOverlay.setVisibility(View.GONE);
        if (controls != null) controls.setVisibility(View.VISIBLE);
        maybeStartGame();
    }

    private void exitApp() {
        nativeStopGame();
        finishAndRemoveTask();
        System.exit(0);
    }

    private void showCreditsDialog() {
        controls.releaseAll();
        boolean ru = isRussian;
        String title = ru ? "Авторы модификации" : "Modification Credits";
        String message = ru ?
            "Grand Theft Auto: Vice City Stories\n"
          + "Версия: VCS Android Beta 1.3.0\n\n"
          + "👨‍💻 Авторы базовых версий (beta 0.1.0 - 0.3.0):\n"
          + "• Jessica_Natalia\n"
          + "• Paulo Daniel Batuani Hurtado\n\n"
          + "🚀 Автор новой версии (VCS Android Beta 1.3.0):\n"
          + "• Alexandercorp287\n\n"
          + "🎮 Базовый рекомпилятор:\n"
          + "• Команда PSPRecomp"
          :
            "Grand Theft Auto: Vice City Stories\n"
          + "Version: VCS Android Beta 1.3.0\n\n"
          + "👨‍💻 Base Version Developers (beta 0.1.0 - 0.3.0):\n"
          + "• Jessica_Natalia\n"
          + "• Paulo Daniel Batuani Hurtado\n\n"
          + "🚀 New Version Author (beta 1.3.0):\n"
          + "• Alexandercorp287\n\n"
          + "🎮 Engine Recompilation:\n"
          + "• PSPRecomp Team";

        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("ОК", null)
            .setOnDismissListener(d -> hideSystemBars())
            .show();
    }

    private void showSettingsDialog() {
        controls.releaseAll();
        boolean ru = isRussian;
        String dpadItem = controls.isShowDpad() ? (ru ? "Скрыть D-Pad (Стрелки)" : "Hide D-Pad") : (ru ? "Показать D-Pad (Стрелки)" : "Show D-Pad");
        boolean showFps = readConfigValue("Display", "showfps") == null ||
            "true".equalsIgnoreCase(readConfigValue("Display", "showfps"));
        String fpsOverlayItem = showFps ? (ru ? "Скрыть счетчик FPS (рядом с радаром)" : "Hide FPS counter") : (ru ? "Показать счетчик FPS (рядом с радаром)" : "Show FPS counter");
        String langItem = ru ? "🌐  Язык / Language: Русский" : "🌐  Language / Язык: English";
        String[] items = {
            ru ? "💾 Сохранить состояние…" : "💾 Save State…",
            ru ? "📂 Загрузить состояние…" : "📂 Load State…",
            ru ? "⚡ Читы и Бонусы…" : "⚡ Cheats & Bonuses…",
            ru ? "🌤️ Погода и Атмосфера…" : "🌤️ Weather & Climate…",
            ru ? "📊 Метрики Vulkan и Производительность…" : "📊 Vulkan Metrics & Performance…",
            ru ? "Внутреннее разрешение…" : "Internal Resolution…",
            ru ? "Частота кадров (FPS)…" : "Frame Rate (FPS)…",
            ru ? "🏙️ Дальность прорисовки…" : "🏙️ Draw Distance…",
            ru ? "Чувствительность камеры…" : "Camera Sensitivity…",
            langItem,
            fpsOverlayItem,
            dpadItem,
            ru ? "Редактировать кнопки" : "Edit Touch Layout",
            ru ? "Сбросить расположение кнопок" : "Reset Touch Layout"
        };
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(ru ? "Настройки" : "Settings")
            .setItems(items, (dialog, which) -> {
                if (which == 0) showSaveStateDialog();
                else if (which == 1) showLoadStateDialog();
                else if (which == 2) showCheatsDialog();
                else if (which == 3) showWeatherDialog();
                else if (which == 4) showVulkanMetricsDialog();
                else if (which == 5) showResolutionDialog();
                else if (which == 6) showFrameRateDialog();
                else if (which == 7) showDrawDistanceDialog();
                else if (which == 8) showCameraSensitivityDialog();
                else if (which == 9) toggleLanguage();
                else if (which == 10) {
                    writeConfigKeys("Display", new String[]{"ShowFPS"}, new String[]{showFps ? "false" : "true"}, "showfps");
                    Toast.makeText(this, showFps ? (ru ? "Счетчик FPS скрыт" : "FPS counter hidden") : (ru ? "Счетчик FPS включен" : "FPS counter shown"), Toast.LENGTH_SHORT).show();
                }
                else if (which == 11) {
                    controls.setShowDpad(!controls.isShowDpad());
                    Toast.makeText(this, controls.isShowDpad() ? (ru ? "D-Pad включен" : "D-Pad enabled") : (ru ? "D-Pad скрыт" : "D-Pad hidden"), Toast.LENGTH_SHORT).show();
                }
                else if (which == 12) {
                    if (mainMenuOverlay != null) mainMenuOverlay.setVisibility(View.GONE);
                    if (controls != null) controls.setVisibility(View.VISIBLE);
                    controls.setEditMode(true);
                }
                else controls.resetLayout();
            })
            .setNegativeButton(ru ? "Закрыть" : "Close", null)
            .setOnDismissListener(d -> hideSystemBars())
            .show();
    }

    private void showQuickSaveLoadDialog() {
        controls.releaseAll();
        String[] items = {"💾 Сохранить состояние…", "📂 Загрузить состояние…"};
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Сохранения")
            .setItems(items, (dialog, which) -> {
                if (which == 0) showSaveStateDialog();
                else showLoadStateDialog();
            })
            .setNegativeButton("Отмена", null)
            .setOnDismissListener(d -> hideSystemBars())
            .show();
    }

    private void showWeatherDialog() {
        controls.releaseAll();
        String[] items = {
            "☀️ Солнечно (Sunny)",
            "🌤️ Ясно (Clear)",
            "🌧️ Дождь (Rainy)",
            "🌫️ Туман (Foggy)"
        };
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Погода и Атмосфера")
            .setItems(items, (dialog, which) -> {
                nativeSendCheat(11 + which);
                Toast.makeText(this, "Погода изменена!", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("Отмена", null)
            .setOnDismissListener(d -> hideSystemBars())
            .show();
    }

    private void showVulkanMetricsDialog() {
        controls.releaseAll();
        int fps = readConfigInt("Timing", "framerate", 60) <= 30 ? 30 : 60;
        String info = "• Графический движок: Vulkan Native GE Backend\n"
                    + "• Экраны/Дисплей: " + panelWidth + "×" + panelHeight + " px\n"
                    + "• Целевой FPS: " + fps + " FPS\n"
                    + "• Архитектура: ARM64-v8a (AOT Recompiled)\n"
                    + "• Память PSP: 32 MB User RAM\n"
                    + "• Аудио-движок: AAudio Native Stream (API 26+)";
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Метрики и Vulkan Инфо")
            .setMessage(info)
            .setPositiveButton("ОК", null)
            .setOnDismissListener(d -> hideSystemBars())
            .show();
    }

    private void showCheatsDialog() {
        controls.releaseAll();
        String[] items = {
            "💚 Здоровье + Ремонт транспорта",
            "🛡️ Полная Броня (100%)",
            "💵 Деньги (+$250,000)",
            "🚔 Снять уровень розыска",
            "🚨 Повысить уровень розыска (+1)",
            "🔫 Набор оружия #1 (Пистолет, Узи, Дробовик...)",
            "🚀 Набор оружия #2 (АК-47, Винтовка, Коктейль Молотова...)",
            "💣 Набор оружия #3 (РПГ, Гранаты, Миниган...)",
            "🪖 Заспавнить Танк (RHINO)",
            "💥 Взорвать все машины вокруг"
        };
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Читы и Бонусы")
            .setItems(items, (dialog, which) -> {
                nativeSendCheat(which + 1);
                Toast.makeText(this, "Чит активирован!", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("Закрыть", null)
            .setOnDismissListener(d -> hideSystemBars())
            .show();
    }

    private void showDrawDistanceDialog() {
        controls.releaseAll();
        String[] labels = {
            "1.0x — Оригинал PSP (стандартная)",
            "1.5x — Увеличенная дальность (рекомендуется)",
            "2.0x — Высокая дальность",
            "3.0x — Максимальная дальность (для мощных устройств)"
        };
        String currentStr = readConfigValue("DrawDistance", "world");
        float currentVal = 1.0f;
        try { if (currentStr != null) currentVal = Float.parseFloat(currentStr); } catch (Exception ignored) {}
        int current = 0;
        if (currentVal >= 2.8f) current = 3;
        else if (currentVal >= 1.8f) current = 2;
        else if (currentVal >= 1.3f) current = 1;

        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Дальность прорисовки")
            .setSingleChoiceItems(labels, current, (dialog, which) -> {
                dialog.dismiss();
                float val = which == 0 ? 1.0f : (which == 1 ? 1.5f : (which == 2 ? 2.0f : 3.0f));
                writeConfigKeys("DrawDistance", new String[]{"Enabled", "World", "Vehicles", "NPCs"},
                    new String[]{"1", String.valueOf(val), String.valueOf(val), String.valueOf(val)}, "enabled", "world");
                Toast.makeText(this, "Дальность прорисовки сохранена!", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("Отмена", null)
            .setOnDismissListener(d -> hideSystemBars())
            .show();
    }

    /**
     * The game's frame rate. VCS itself draws 30 frames a second on the PSP;
     * 60 doubles every frame's work, which a low-end phone cannot keep up with.
     */
    private void showFrameRateDialog() {
        controls.releaseAll();
        String[] labels = {"30 FPS — как на PSP (рекомендуется для слабых устройств)",
                           "60 FPS — максимально плавно (требует мощный процессор)"};
        int current = readConfigInt("Timing", "framerate", 60) <= 30 ? 0 : 1;
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Частота кадров (FPS)")
            .setSingleChoiceItems(labels, current, (dialog, which) -> {
                dialog.dismiss();
                if (which == current) return;
                if (!writeConfigKeys("Timing", new String[]{"FrameRate"},
                        new String[]{which == 0 ? "30" : "60"}, "fps", "targetfps")) {
                    statusView.setVisibility(View.VISIBLE);
                    statusView.setText("Не удалось сохранить частоту кадров.");
                    return;
                }
                confirmRestart();
            })
            .setNegativeButton("Отмена", null)
            .setOnDismissListener(d -> hideSystemBars())
            .show();
    }

    /**
     * First launch on a slow phone starts at the PSP's own resolution and frame
     * rate, so a tester with a Helio G85 does not meet the game at its heaviest.
     * Settings the player already chose are left alone.
     */
    private void applyLowEndDefaultsOnce() {
        android.content.SharedPreferences prefs = getSharedPreferences("controls", Context.MODE_PRIVATE);
        if (prefs.getBoolean("low_end_checked", false)) return;
        prefs.edit().putBoolean("low_end_checked", true).apply();
        long fastestKhz = 0;
        for (int cpu = 0; cpu < 32; ++cpu) {
            File file = new File("/sys/devices/system/cpu/cpu" + cpu + "/cpufreq/cpuinfo_max_freq");
            if (!file.isFile()) continue;
            try {
                fastestKhz = Math.max(fastestKhz,
                    Long.parseLong(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).trim()));
            } catch (IOException | NumberFormatException ignored) {
            }
        }
        android.app.ActivityManager activity = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
        android.app.ActivityManager.MemoryInfo memory = new android.app.ActivityManager.MemoryInfo();
        if (activity != null) activity.getMemoryInfo(memory);
        // Below ~2.6 GHz peak (Helio G-series, Snapdragon 6xx/7xx) or under 4 GB.
        boolean lowEnd = (fastestKhz > 0 && fastestKhz < 2_600_000L) ||
            (activity != null && activity.isLowRamDevice()) ||
            (memory.totalMem > 0 && memory.totalMem < 4L * 1024 * 1024 * 1024);
        if (!lowEnd) return;
        if (readConfigValue("Rendering", "internalscale") == null &&
            readConfigValue("Rendering", "internalresolutionmode") == null)
            writeResolution(new ResolutionChoice("", "Scale", 1));
        if (readConfigValue("Timing", "framerate") == null)
            writeConfigKeys("Timing", new String[]{"FrameRate"}, new String[]{"30"}, "fps", "targetfps");
    }

    /** How far the camera turns per finger drag. Takes effect at once. */
    private void showCameraSensitivityDialog() {
        controls.releaseAll();
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Чувствительность камеры")
            .setSingleChoiceItems(CAMERA_SENSITIVITY_LABELS, cameraSensitivity, (dialog, which) -> {
                cameraSensitivity = which;
                getSharedPreferences("controls", Context.MODE_PRIVATE).edit()
                    .putInt("camera_sensitivity", which).apply();
                dialog.dismiss();
            })
            .setNegativeButton("Отмена", null)
            .setOnDismissListener(d -> hideSystemBars())
            .show();
    }

    private File stateFile(int slot) {
        return new File(new File(getFilesDir(), "savestates"), "slot" + slot + ".vcss");
    }

    /** Explicit Save State Dialog */
    private void showSaveStateDialog() {
        controls.releaseAll();
        if (stateBusy) return;
        DateFormat format = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT);
        String[] items = new String[STATE_SLOTS];
        for (int i = 0; i < STATE_SLOTS; ++i) {
            File file = stateFile(i + 1);
            items[i] = "💾 Слот " + (i + 1) + " — "
                + (file.isFile() ? "Занят (" + format.format(new Date(file.lastModified())) + ")" : "Свободен");
        }
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Сохранить состояние")
            .setItems(items, (dialog, which) -> {
                int slot = which + 1;
                File file = stateFile(slot);
                if (file.isFile()) {
                    new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle("Перезаписать Слот " + slot + "?")
                        .setMessage("В этом слоте уже есть сохранение. Перезаписать его?")
                        .setPositiveButton("Перезаписать", (d, w) -> runStateOperation(true, file))
                        .setNegativeButton("Отмена", null)
                        .setOnDismissListener(d -> hideSystemBars())
                        .show();
                } else {
                    runStateOperation(true, file);
                }
            })
            .setNegativeButton("Отмена", null)
            .setOnDismissListener(d -> hideSystemBars())
            .show();
    }

    /** Explicit Load State Dialog */
    private void showLoadStateDialog() {
        controls.releaseAll();
        if (stateBusy) return;
        boolean ru = isRussian;
        DateFormat format = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT);
        String[] items = new String[STATE_SLOTS];
        for (int i = 0; i < STATE_SLOTS; ++i) {
            File file = stateFile(i + 1);
            items[i] = "📂 " + (ru ? "Слот " : "Slot ") + (i + 1) + " — "
                + (file.isFile() ? format.format(new Date(file.lastModified())) : (ru ? "Пусто" : "Empty"));
        }
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(ru ? "Загрузить состояние" : "Load State")
            .setItems(items, (dialog, which) -> {
                int slot = which + 1;
                File file = stateFile(slot);
                if (!file.isFile()) {
                    Toast.makeText(this, ru ? "Слот " + slot + " пуст!" : "Slot " + slot + " is empty!", Toast.LENGTH_SHORT).show();
                    return;
                }
                gameLaunchAllowed = true;
                if (!startRequested) {
                    startNewGame();
                    handler.postDelayed(() -> runStateOperation(false, file), 1400);
                } else {
                    if (mainMenuOverlay != null) mainMenuOverlay.setVisibility(View.GONE);
                    if (controls != null) controls.setVisibility(View.VISIBLE);
                    runStateOperation(false, file);
                }
            })
            .setNegativeButton(ru ? "Отмена" : "Cancel", null)
            .setOnDismissListener(d -> hideSystemBars())
            .show();
    }

    private void runStateOperation(boolean save, File file) {
        stateBusy = true;
        Toast.makeText(this, save ? "Сохранение состояния…" : "Загрузка состояния…", Toast.LENGTH_SHORT).show();
        // The native side waits for the game's next frame, so not on the UI thread.
        new Thread(() -> {
            String message = nativeSaveState(save, file.getAbsolutePath());
            runOnUiThread(() -> {
                stateBusy = false;
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
            });
        }, "VCSSaveState").start();
    }

    private void showResolutionDialog() {
        controls.releaseAll();
        List<ResolutionChoice> choices = resolutionChoices();
        String[] labels = new String[choices.size()];
        for (int i = 0; i < labels.length; ++i) labels[i] = choices.get(i).label;
        int current = currentResolutionIndex(choices);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Внутреннее разрешение")
            .setSingleChoiceItems(labels, current, (dialog, which) -> {
                dialog.dismiss();
                if (which == current) return;
                if (!writeResolution(choices.get(which))) {
                    statusView.setVisibility(View.VISIBLE);
                    statusView.setText("Не удалось сохранить разрешение.");
                    return;
                }
                confirmRestart();
            })
            .setNegativeButton("Отмена", null)
            .setOnDismissListener(d -> hideSystemBars())
            .show();
    }

    private void confirmRestart() {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Перезапустите игру")
            .setMessage("Новое разрешение применится после перезапуска игры. "
                + "Несохраненный прогресс будет утерян.")
            .setPositiveButton("Перезапустить сейчас", (d, w) -> restartApp())
            .setNegativeButton("Позже", null)
            .setOnDismissListener(d -> hideSystemBars())
            .show();
    }

    private void restartApp() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (launch == null) return;
        Intent restart = Intent.makeRestartActivityTask(launch.getComponent());
        startActivity(restart);
        // The renderer is configured once per process; a fresh one picks up the new size.
        Runtime.getRuntime().exit(0);
    }

    private File configFile() {
        return new File(getFilesDir(), CONFIG_FILE);
    }

    private List<String> readConfig() {
        try {
            File file = configFile();
            if (file.isFile()) return new ArrayList<>(Files.readAllLines(file.toPath(), StandardCharsets.UTF_8));
        } catch (IOException ignored) {
        }
        return new ArrayList<>();
    }

    private static String keyOf(String line) {
        int eq = line.indexOf('=');
        return eq < 0 ? "" : line.substring(0, eq).trim().toLowerCase(Locale.ROOT);
    }

    private static String valueOf(String line) {
        int eq = line.indexOf('=');
        return eq < 0 ? "" : line.substring(eq + 1).trim();
    }

    private int currentResolutionIndex(List<ResolutionChoice> choices) {
        String mode = "scale";
        int scale = 3; // the Android default, see RenderingConfiguration
        boolean inRendering = false;
        for (String raw : readConfig()) {
            String line = raw.trim();
            if (line.startsWith("[")) {
                inRendering = line.equalsIgnoreCase("[Rendering]");
                continue;
            }
            if (!inRendering) continue;
            String key = keyOf(line);
            if (key.equals("internalresolutionmode") || key.equals("internalmode"))
                mode = valueOf(line).toLowerCase(Locale.ROOT);
            else if (key.equals("internalscale") || key.equals("scale")) {
                try { scale = Integer.parseInt(valueOf(line)); } catch (NumberFormatException ignored) { }
            }
        }
        for (int i = 0; i < choices.size(); ++i) {
            ResolutionChoice c = choices.get(i);
            if (c.mode.equalsIgnoreCase("Desktop") &&
                (mode.equals("desktop") || mode.equals("native") || mode.equals("monitor"))) return i;
            if (c.mode.equalsIgnoreCase("Scale") && mode.startsWith("scale") && c.scale == scale) return i;
        }
        return -1;
    }

    /** The value of `key` (lower case) in `[section]`, or null when absent. */
    private String readConfigValue(String section, String key) {
        boolean inSection = false;
        String found = null;
        for (String raw : readConfig()) {
            String line = raw.trim();
            if (line.startsWith("[")) {
                inSection = line.equalsIgnoreCase("[" + section + "]");
                continue;
            }
            if (inSection && keyOf(line).equals(key)) found = valueOf(line);
        }
        return found;
    }

    private int readConfigInt(String section, String key, int fallback) {
        String value = readConfigValue(section, key);
        try {
            return value != null ? Integer.parseInt(value) : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * Sets `keys` to `values` in `[section]`, dropping any earlier spelling of
     * them (and of `aliases`) and keeping everything else in the file.
     */
    private boolean writeConfigKeys(String section, String[] keys, String[] values, String... aliases) {
        List<String> replaced = new ArrayList<>();
        for (String key : keys) replaced.add(key.toLowerCase(Locale.ROOT));
        for (String alias : aliases) replaced.add(alias.toLowerCase(Locale.ROOT));
        List<String> ours = new ArrayList<>();
        for (int i = 0; i < keys.length; ++i) ours.add(keys[i] + "=" + values[i]);
        List<String> out = new ArrayList<>();
        boolean inSection = false, sawSection = false;
        for (String raw : readConfig()) {
            String line = raw.trim();
            if (line.startsWith("[")) {
                inSection = line.equalsIgnoreCase("[" + section + "]");
                out.add(raw);
                if (inSection && !sawSection) {
                    sawSection = true;
                    out.addAll(ours);
                }
                continue;
            }
            if (inSection && replaced.contains(keyOf(line))) continue;
            out.add(raw);
        }
        if (!sawSection) {
            out.add("[" + section + "]");
            if (section.equals("Rendering")) out.add("Backend=Vulkan");
            out.addAll(ours);
        }
        try {
            Files.write(configFile().toPath(), out, StandardCharsets.UTF_8);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Rewrites only the resolution keys of [Rendering], keeping everything else in the file. */
    private boolean writeResolution(ResolutionChoice choice) {
        if (choice.scale > 0)
            return writeConfigKeys("Rendering", new String[]{"InternalResolutionMode", "InternalScale"},
                new String[]{choice.mode, String.valueOf(choice.scale)}, "internalmode", "scale");
        return writeConfigKeys("Rendering", new String[]{"InternalResolutionMode"},
            new String[]{choice.mode}, "internalmode", "internalscale", "scale");
    }

    // --- Surface ---------------------------------------------------------------------------

    @Override public void surfaceCreated(SurfaceHolder holder) {
        nativeSetSurface(holder.getSurface());
        surfaceReady = true;
        if (gameLaunchAllowed) {
            maybeStartGame();
        }
    }
    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        nativeSetSurface(holder.getSurface());
    }
    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceReady = false;
        nativeSetSurface(null);
    }
    @Override protected void onDestroy() {
        handler.removeCallbacks(statusPoll);
        nativeStopGame();
        super.onDestroy();
    }

    /**
     * The game picture, edge to edge. The game renders widescreen (Hor+) at the
     * panel's own aspect - Widescreen is on by default on Android - so filling
     * the screen shows more of the world rather than stretching it.
     */
    private final class GameSurface extends SurfaceView {
        GameSurface() { super(MainActivity.this); }
    }
}
