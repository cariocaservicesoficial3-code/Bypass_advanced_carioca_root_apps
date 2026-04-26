package com.carioca.dtmfautodialer.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.telecom.TelecomManager;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.carioca.dtmfautodialer.ui.MainActivity;

import java.util.ArrayList;
import java.util.List;

/**
 * FloatingWidgetService v1.4
 * 
 * Agora usa comandos 'input keyevent' via ROOT para digitar os números.
 * É o método mais infalível porque simula o teclado real do sistema,
 * independente de hooks no app de telefone.
 */
public class FloatingWidgetService extends Service {

    private WindowManager windowManager;
    private View floatingView;
    private View expandedView;
    private View minimizedView;
    private boolean isExpanded = false;

    private EditText editDigits;
    private TextView tvStatus;
    private TextView tvProgress;
    private ProgressBar progressBar;
    private Button btnSend;
    private Button btnStop;
    private SeekBar seekDelay;
    private TextView tvDelay;

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

    private void safeVibrate(long ms) {
        try {
            android.os.Vibrator v = (android.os.Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null && v.hasVibrator()) v.vibrate(ms);
        } catch (Exception e) { }
    }

    private void executeShellCommand(String command) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", command});
            // Não esperamos o término para não travar a UI
        } catch (Exception e) {
            XLog("Erro root: " + e.getMessage());
        }
    }

    private void XLog(String msg) {
        if (tvStatus != null) {
            handler.post(() -> tvStatus.setText(msg));
        }
    }

    private void sendDtmfSequence() {
        String digits = editDigits.getText().toString().replaceAll("[^0-9*#]", "");
        if (digits.isEmpty()) {
            Toast.makeText(this, "Nenhum dígito válido!", Toast.LENGTH_SHORT).show();
            return;
        }

        isPlaying = true;
        btnSend.setEnabled(false);
        btnSend.setAlpha(0.5f);
        btnStop.setEnabled(true);
        btnStop.setAlpha(1.0f);
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setMax(digits.length());
        tvProgress.setVisibility(View.VISIBLE);

        final char[] chars = digits.toCharArray();
        
        for (int i = 0; i < chars.length; i++) {
            final int index = i;
            final char c = chars[i];
            
            handler.postDelayed(() -> {
                if (!isPlaying) return;

                int keyCode = getKeyCodeForChar(c);
                if (keyCode != -1) {
                    // Simula o pressionamento da tecla via ROOT
                    executeShellCommand("input keyevent " + keyCode);
                    
                    XLog("Digitando: " + c);
                    progressBar.setProgress(index + 1);
                    tvProgress.setText((index + 1) + "/" + chars.length);
                }

                if (index == chars.length - 1) {
                    finishSequence("Concluído!");
                }
            }, (long) i * currentDelay);
        }
    }

    private int getKeyCodeForChar(char c) {
        if (c >= '0' && c <= '9') return 7 + (c - '0'); // KEYCODE_0 é 7, KEYCODE_9 é 16
        if (c == '*') return 17; // KEYCODE_STAR
        if (c == '#') return 18; // KEYCODE_POUND
        return -1;
    }

    private void finishSequence(String msg) {
        isPlaying = false;
        handler.post(() -> {
            btnSend.setEnabled(true);
            btnSend.setAlpha(1.0f);
            btnStop.setEnabled(false);
            btnStop.setAlpha(0.5f);
            progressBar.setVisibility(View.GONE);
            tvProgress.setVisibility(View.GONE);
            XLog(msg);
            safeVibrate(200);
        });
    }

    private void stopDtmfSequence() {
        isPlaying = false;
        finishSequence("Interrompido");
    }

    // ============================================================
    // UI CODE (Simplificado para v1.4)
    // ============================================================

    private void createFloatingWidget() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        LinearLayout mainLayout = new LinearLayout(this);
        mainLayout.setOrientation(LinearLayout.VERTICAL);

        minimizedView = createMinimizedView();
        expandedView = createExpandedView();
        expandedView.setVisibility(View.GONE);

        mainLayout.addView(minimizedView);
        mainLayout.addView(expandedView);
        floatingView = mainLayout;

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? 
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : 
                        WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 100; params.y = 200;

        windowManager.addView(floatingView, params);
        setupDrag(params);
    }

    private View createMinimizedView() {
        TextView v = new TextView(this);
        v.setText("#"); v.setTextColor(Color.WHITE); v.setTextSize(24);
        v.setGravity(Gravity.CENTER);
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.OVAL);
        gd.setColor(Color.parseColor("#7C3AED"));
        v.setBackground(gd);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(56), dp(56)));
        v.setOnClickListener(view -> toggle());
        return v;
    }

    private View createExpandedView() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(16), dp(16), dp(16), dp(16));
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(Color.parseColor("#1E1B2E"));
        gd.setCornerRadius(dp(16));
        gd.setStroke(dp(1), Color.parseColor("#7C3AED"));
        layout.setBackground(gd);

        TextView title = new TextView(this);
        title.setText("DTMF Auto Dialer v1.4");
        title.setTextColor(Color.WHITE);
        title.setTypeface(null, Typeface.BOLD);
        layout.addView(title);

        tvStatus = new TextView(this);
        tvStatus.setText("Pronto para digitar");
        tvStatus.setTextColor(Color.parseColor("#A78BFA"));
        tvStatus.setTextSize(12);
        layout.addView(tvStatus);

        editDigits = new EditText(this);
        editDigits.setHint("Cole os números");
        editDigits.setTextColor(Color.WHITE);
        editDigits.setHintTextColor(Color.GRAY);
        layout.addView(editDigits);

        Button btnPaste = new Button(this);
        btnPaste.setText("COLAR");
        btnPaste.setOnClickListener(v -> {
            ClipboardManager cb = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cb.hasPrimaryClip()) {
                editDigits.setText(cb.getPrimaryClip().getItemAt(0).getText());
            }
        });
        layout.addView(btnPaste);

        tvDelay = new TextView(this);
        tvDelay.setText("Delay: 300ms");
        tvDelay.setTextColor(Color.WHITE);
        layout.addView(tvDelay);

        seekDelay = new SeekBar(this);
        seekDelay.setMax(1000); seekDelay.setProgress(300);
        seekDelay.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int i, boolean b) {
                currentDelay = Math.max(100, i);
                tvDelay.setText("Delay: " + currentDelay + "ms");
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        layout.addView(seekDelay);

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setVisibility(View.GONE);
        layout.addView(progressBar);

        tvProgress = new TextView(this);
        tvProgress.setTextColor(Color.GREEN);
        tvProgress.setVisibility(View.GONE);
        layout.addView(tvProgress);

        LinearLayout row = new LinearLayout(this);
        btnSend = new Button(this); btnSend.setText("DIGITAR");
        btnSend.setOnClickListener(v -> sendDtmfSequence());
        btnStop = new Button(this); btnStop.setText("PARAR");
        btnStop.setEnabled(false); btnStop.setAlpha(0.5f);
        btnStop.setOnClickListener(v -> stopDtmfSequence());
        row.addView(btnSend); row.addView(btnStop);
        layout.addView(row);

        Button btnClose = new Button(this); btnClose.setText("FECHAR");
        btnClose.setOnClickListener(v -> toggle());
        layout.addView(btnClose);

        layout.setLayoutParams(new LinearLayout.LayoutParams(dp(280), LinearLayout.LayoutParams.WRAP_CONTENT));
        return layout;
    }

    private void toggle() {
        isExpanded = !isExpanded;
        minimizedView.setVisibility(isExpanded ? View.GONE : View.VISIBLE);
        expandedView.setVisibility(isExpanded ? View.VISIBLE : View.GONE);
        WindowManager.LayoutParams p = (WindowManager.LayoutParams) floatingView.getLayoutParams();
        if (isExpanded) p.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        else p.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        windowManager.updateViewLayout(floatingView, p);
    }

    private void setupDrag(WindowManager.LayoutParams params) {
        minimizedView.setOnTouchListener(new View.OnTouchListener() {
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
                .setContentTitle("DTMF Auto Dialer").setSmallIcon(android.R.drawable.ic_menu_call).build();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (floatingView != null) windowManager.removeView(floatingView);
    }
}
