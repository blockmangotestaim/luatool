package com.luatool.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

/** Draws a draggable floating button and the control panel above other apps. */
public class OverlayService extends Service {
    static volatile OverlayService instance;

    Context themed;
    private WindowManager wm;
    private TextView bubble;
    private PanelView panel;
    private WindowManager.LayoutParams bubbleLp, panelLp;
    private boolean bubbleShown, panelShown;
    private int gx, gy, gw, gh;   // panel geometry for the current orientation

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        Session.app = getApplicationContext();
        if (bubble == null) setup();
        return START_STICKY;
    }

    private void setup() {
        instance = this;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        themed = new ContextThemeWrapper(this, android.R.style.Theme_Material);
        startForegroundSafely();

        bubble = new TextView(themed);
        bubble.setText("Lua");
        bubble.setTextColor(Color.WHITE);
        bubble.setTextSize(13);
        bubble.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0xCC3949AB);
        bg.setStroke(Ui.dp(this, 2), 0xFFFFFFFF);
        bubble.setBackground(bg);

        bubbleLp = new WindowManager.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 52), UiBridge.overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        bubbleLp.gravity = Gravity.TOP | Gravity.START;
        bubbleLp.x = Ui.dp(this, 8);
        bubbleLp.y = Ui.dp(this, 160);

        bubble.setOnTouchListener(new View.OnTouchListener() {
            int sx, sy;
            float tx, ty;
            boolean moved;

            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        sx = bubbleLp.x; sy = bubbleLp.y;
                        tx = e.getRawX(); ty = e.getRawY();
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - tx, dy = e.getRawY() - ty;
                        if (Math.abs(dx) > Ui.dp(OverlayService.this, 6) || Math.abs(dy) > Ui.dp(OverlayService.this, 6)) moved = true;
                        if (moved) {
                            bubbleLp.x = sx + (int) dx;
                            bubbleLp.y = sy + (int) dy;
                            try { wm.updateViewLayout(bubble, bubbleLp); } catch (Throwable ignored) {}
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!moved) onBubbleTap();
                        return true;
                }
                return false;
            }
        });

        try {
            wm.addView(bubble, bubbleLp);
            bubbleShown = true;
            AppLog.add("Floating button is on. Tap it to open the panel.");
        } catch (Throwable t) {
            AppLog.add("Overlay failed: " + t.getMessage()
                    + "  -> allow 'Display over other apps' for this app (or for the host app, e.g. BlackBox).");
            instance = null;
            stopSelf();
        }
    }

    private void onBubbleTap() {
        // A script that polls gg.isClickedUiButton() gets the click instead of the panel opening.
        if (Session.running && System.currentTimeMillis() - Session.lastClickPoll < 2000) {
            Session.clicked = true;
            return;
        }
        if (panelShown) hidePanelNow(); else showPanelNow();
    }

    // ---------- panel geometry (adjustable + responsive) ----------

    private SharedPreferences prefs() {
        try { return getSharedPreferences("luatool_ui", MODE_PRIVATE); } catch (Throwable t) { return null; }
    }

    private int screenW() { return getResources().getDisplayMetrics().widthPixels; }
    private int screenH() { return getResources().getDisplayMetrics().heightPixels; }

    private void loadGeometry() {
        int W = screenW(), H = screenH();
        boolean land = W > H;
        String o = land ? "l" : "p";
        SharedPreferences sp = prefs();
        int w = sp != null ? sp.getInt(o + "_w", 0) : 0;
        int h = sp != null ? sp.getInt(o + "_h", 0) : 0;
        if (w <= 0 || h <= 0) {
            w = land ? Math.min((int) (W * 0.70f), Ui.dp(this, 560)) : Math.min((int) (W * 0.94f), Ui.dp(this, 440));
            h = land ? (int) (H * 0.88f) : (int) (H * 0.62f);
            gx = (W - w) / 2;
            gy = (H - h) / 2;
        } else {
            gx = sp.getInt(o + "_x", 0);
            gy = sp.getInt(o + "_y", 0);
        }
        gw = w;
        gh = h;
        clampGeometry();
    }

    private void clampGeometry() {
        int W = screenW(), H = screenH();
        gw = Math.min(W, Math.max(Ui.dp(this, 220), gw));
        gh = Math.min(H, Math.max(Ui.dp(this, 150), gh));
        gx = Math.max(0, Math.min(gx, W - gw));
        gy = Math.max(0, Math.min(gy, H - gh));
    }

    private void saveGeometry() {
        SharedPreferences sp = prefs();
        if (sp == null) return;
        String o = screenW() > screenH() ? "l" : "p";
        try {
            sp.edit().putInt(o + "_w", gw).putInt(o + "_h", gh).putInt(o + "_x", gx).putInt(o + "_y", gy).apply();
        } catch (Throwable ignored) {}
    }

    private void applyGeometry() {
        if (panelLp == null) return;
        panelLp.width = gw;
        panelLp.height = gh;
        panelLp.x = gx;
        panelLp.y = gy;
        if (panelShown) {
            try { wm.updateViewLayout(panel, panelLp); } catch (Throwable ignored) {}
        }
    }

    @Override public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // rotation: load the size saved for the new orientation and keep everything on screen
        loadGeometry();
        applyGeometry();
        if (bubbleLp != null) {
            bubbleLp.x = Math.max(0, Math.min(bubbleLp.x, screenW() - bubbleLp.width));
            bubbleLp.y = Math.max(0, Math.min(bubbleLp.y, screenH() - bubbleLp.height));
            if (bubbleShown) {
                try { wm.updateViewLayout(bubble, bubbleLp); } catch (Throwable ignored) {}
            }
        }
    }

    private void showPanelNow() {
        if (panelShown) return;
        try {
            if (panel == null) {
                panel = new PanelView(themed);
                panel.setOnClose(new Runnable() { @Override public void run() { hidePanelNow(); } });
                panel.setHost(new PanelView.Host() {
                    @Override public void move(int dx, int dy) { gx += dx; gy += dy; clampGeometry(); applyGeometry(); }
                    @Override public void resize(int dw, int dh) { gw += dw; gh += dh; clampGeometry(); applyGeometry(); }
                    @Override public void done() { saveGeometry(); }
                });
                panelLp = new WindowManager.LayoutParams(Ui.dp(this, 300), Ui.dp(this, 300), UiBridge.overlayType(),
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        PixelFormat.TRANSLUCENT);
                panelLp.gravity = Gravity.TOP | Gravity.START;
            }
            loadGeometry();
            panelLp.width = gw;
            panelLp.height = gh;
            panelLp.x = gx;
            panelLp.y = gy;
            panel.refreshAll();
            wm.addView(panel, panelLp);
            panelShown = true;
        } catch (Throwable t) {
            AppLog.add("Cannot open panel: " + t.getMessage());
        }
    }

    private void hidePanelNow() {
        if (!panelShown) return;
        try { wm.removeView(panel); } catch (Throwable ignored) {}
        panelShown = false;
    }

    private void setBubbleNow(boolean show) {
        if (bubble == null) return;
        try {
            if (show && !bubbleShown) { wm.addView(bubble, bubbleLp); bubbleShown = true; }
            else if (!show && bubbleShown) { wm.removeView(bubble); bubbleShown = false; }
        } catch (Throwable ignored) {}
    }

    // ---------- called from the Lua thread ----------

    static boolean panelVisible() {
        OverlayService s = instance;
        return s != null && s.panelShown;
    }

    static void setPanelVisible(final boolean v) {
        final OverlayService s = instance;
        if (s == null) return;
        UiBridge.main.post(new Runnable() {
            @Override public void run() { if (v) s.showPanelNow(); else s.hidePanelNow(); }
        });
    }

    static void setBubbleVisible(final boolean v) {
        final OverlayService s = instance;
        if (s == null) return;
        UiBridge.main.post(new Runnable() {
            @Override public void run() { s.setBubbleNow(v); }
        });
    }

    private void startForegroundSafely() {
        try {
            Notification.Builder nb;
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                nm.createNotificationChannel(new NotificationChannel("luatool", "LuaTool", NotificationManager.IMPORTANCE_MIN));
                nb = new Notification.Builder(this, "luatool");
            } else {
                nb = new Notification.Builder(this);
            }
            nb.setContentTitle("LuaTool floating menu is running")
              .setSmallIcon(android.R.drawable.ic_menu_edit);
            startForeground(1, nb.build());
        } catch (Throwable ignored) {
            // some virtual environments cannot post notifications; the overlay still works
        }
    }

    @Override public void onDestroy() {
        hidePanelNow();
        setBubbleNow(false);
        instance = null;
        super.onDestroy();
    }
}
