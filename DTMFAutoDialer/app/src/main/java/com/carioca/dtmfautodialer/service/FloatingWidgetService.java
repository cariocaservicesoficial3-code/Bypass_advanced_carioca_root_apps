package com.carioca.dtmfautodialer.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

/**
 * FloatingWidgetService v1.5
 * 
 * UI Simplificada: Painel único, sem animação de expandir/minimizar para evitar falhas de toque.
 * Usa ROOT (input keyevent) para digitar.
 */
public class FloatingWidgetService extends Service {

    private WindowManager windowManager;
    private View floatingView;
    private WindowManager.LayoutParams params;

    private EditText editDigits;
    private TextView tvStatus;
    private ProgressBar progressBar;
    private Button btnSend;
    private int currentDelay = 300;
    private boolean isPlaying = false;
    private Handler handler = new Handler(Looper.getMainLooper());

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(1337, createNotification());
        createFloatingWidget();
    }

    private void createFloatingWidget() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        // Layout Principal
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(12), dp(12), dp(12), dp(12));
        
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(Color.parseColor("#E61E1B2E")); // 90% opaco
        gd.setCornerRadius(dp(16));
        gd.setStroke(dp(2), Color.parseColor("#7C3AED"));
        layout.setBackground(gd);

        // Header / Drag Handle
        TextView title = new TextView(this);
        title.setText("● DTMF AUTO DIALER v1.5");
        title.setTextColor(Color.WHITE);
        title.setTextSize(12);
        title.setTypeface(null, Typeface.BOLD);
        title.setPadding(0, 0, 0, dp(8));
        layout.addView(title);

        // Status
        tvStatus = new TextView(this);
        tvStatus.setText("Pronto (Use ROOT)");
        tvStatus.setTextColor(Color.parseColor("#A78BFA"));
        tvStatus.setTextSize(11);
        layout.addView(tvStatus);

        // Campo de Texto
        editDigits = new EditText(this);
        editDigits.setHint("Cole os números aqui");
        editDigits.setHintTextColor(Color.GRAY);
        editDigits.setTextColor(Color.WHITE);
        editDigits.setTextSize(16);
        editDigits.setBackgroundColor(Color.parseColor("#33FFFFFF"));
        editDigits.setPadding(dp(8), dp(8), dp(8), dp(8));
        layout.addView(editDigits);

        // Botões Row 1
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setPadding(0, dp(8), 0, 0);

        Button btnPaste = createButton("COLAR", "#4F46E5");
        btnPaste.setOnClickListener(v -> {
            ClipboardManager cb = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cb.hasPrimaryClip()) {
                editDigits.setText(cb.getPrimaryClip().getItemAt(0).getText());
            }
        });
        
        Button btnClear = createButton("LIMPAR", "#4B5563");
        btnClear.setOnClickListener(v -> editDigits.setText(""));

        row1.addView(btnPaste, new LinearLayout.LayoutParams(0, dp(36), 1));
        row1.addView(btnClear, new LinearLayout.LayoutParams(0, dp(36), 1));
        layout.addView(row1);

        // Delay Slider
        TextView tvDelay = new TextView(this);
        tvDelay.setText("Delay: 300ms");
        tvDelay.setTextColor(Color.WHITE);
        tvDelay.setTextSize(11);
        tvDelay.setPadding(0, dp(8), 0, 0);
        layout.addView(tvDelay);

        SeekBar seekDelay = new SeekBar(this);
        seekDelay.setMax(1000);
        seekDelay.setProgress(300);
        seekDelay.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int i, boolean b) {
                currentDelay = Math.max(100, i);
                tvDelay.setText("Delay: " + currentDelay + "ms");
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        layout.addView(seekDelay);

        // Progress
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setVisibility(View.GONE);
        layout.addView(progressBar);

        // Botões Row 2 (Ação)
        LinearLayout row2 = new LinearLayout(this);
        row2.setPadding(0, dp(8), 0, 0);

        btnSend = createButton("DIGITAR", "#7C3AED");
        btnSend.setOnClickListener(v -> startTyping());

        Button btnClose = createButton("X", "#EF4444");
        btnClose.setOnClickListener(v -> stopSelf());

        row2.addView(btnSend, new LinearLayout.LayoutParams(0, dp(44), 4));
        row2.addView(btnClose, new LinearLayout.LayoutParams(0, dp(44), 1));
        layout.addView(row2);

        floatingView = layout;

        params = new WindowManager.LayoutParams(
                dp(260),
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? 
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : 
                        WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 100; params.y = 200;

        windowManager.addView(floatingView, params);

        // Drag logic no título
        title.setOnTouchListener(new View.OnTouchListener() {
            private int initialX, initialY;
            private float initialTouchX, initialTouchY;
            @Override public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = params.x; initialY = params.y;
                        initialTouchX = event.getRawX(); initialTouchY = event.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        params.x = initialX + (int) (event.getRawX() - initialTouchX);
                        params.y = initialY + (int) (event.getRawY() - initialTouchY);
                        windowManager.updateViewLayout(floatingView, params);
                        return true;
                }
                return false;
            }
        });
    }

    private Button createButton(String text, String color) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(12);
        b.setTypeface(null, Typeface.BOLD);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(Color.parseColor(color));
        gd.setCornerRadius(dp(8));
        b.setBackground(gd);
        return b;
    }

    private void startTyping() {
        String digits = editDigits.getText().toString().replaceAll("[^0-9*#]", "");
        if (digits.isEmpty()) return;

        isPlaying = true;
        btnSend.setEnabled(false);
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setMax(digits.length());
        
        final char[] chars = digits.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            final int index = i;
            final char c = chars[i];
            handler.postDelayed(() -> {
                if (!isPlaying) return;
                
                int keyCode = -1;
                if (c >= '0' && c <= '9') keyCode = 7 + (c - '0');
                else if (c == '*') keyCode = 17;
                else if (c == '#') keyCode = 18;

                if (keyCode != -1) {
                    try {
                        Runtime.getRuntime().exec(new String[]{"su", "-c", "input keyevent " + keyCode});
                    } catch (Exception e) {}
                }

                tvStatus.setText("Digitando: " + c + " (" + (index+1) + "/" + chars.length + ")");
                progressBar.setProgress(index + 1);

                if (index == chars.length - 1) {
                    isPlaying = false;
                    btnSend.setEnabled(true);
                    progressBar.setVisibility(View.GONE);
                    tvStatus.setText("Concluído!");
                }
            }, (long) i * currentDelay);
        }
    }

    private int dp(int dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, getResources().getDisplayMetrics());
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel("dtmf", "DTMF", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private Notification createNotification() {
        return (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? 
                new Notification.Builder(this, "dtmf") : new Notification.Builder(this))
                .setContentTitle("DTMF Ativo").setSmallIcon(android.R.drawable.ic_menu_call).build();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (floatingView != null) windowManager.removeView(floatingView);
    }
}
