package com.carioca.dtmfautodialer.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
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

import com.carioca.dtmfautodialer.ui.MainActivity;

/**
 * FloatingWidgetService v1.8
 *
 * Widget overlay flutuante — modo alternativo quando o app não é o discador padrão.
 * Usa ROOT (su input keyevent) para enviar teclas ao app de telefone em foco.
 *
 * Melhorias v1.8:
 * - Botão de minimizar/expandir
 * - Delay configurável via SeekBar
 * - Botão para abrir o app principal
 * - Notificação com ação para fechar
 */
public class FloatingWidgetService extends Service {

    private static final String CHANNEL_ID = "dtmf_widget";
    private static final int NOTIF_ID = 1337;

    private WindowManager windowManager;
    private View floatingView;
    private WindowManager.LayoutParams params;

    private EditText editDigits;
    private TextView tvStatus;
    private ProgressBar progressBar;
    private Button btnSend;
    private LinearLayout contentLayout; // layout que pode ser minimizado
    private boolean isMinimized = false;

    private int currentDelay = 500;
    private boolean isPlaying = false;
    private Handler handler = new Handler(Looper.getMainLooper());

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIF_ID, createNotification());
        createFloatingWidget();
    }

    private void createFloatingWidget() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        // Container principal
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(10), dp(12), dp(10));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#F01E1B2E"));
        bg.setCornerRadius(dp(16));
        bg.setStroke(dp(2), Color.parseColor("#7C3AED"));
        root.setBackground(bg);

        // --- Header (arrastável + botões minimizar/fechar) ---
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, 0, 0, dp(6));

        TextView title = new TextView(this);
        title.setText("⌨ DTMF Auto Dialer");
        title.setTextColor(Color.WHITE);
        title.setTextSize(13);
        title.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        header.addView(title, titleParams);

        Button btnMin = createSmallButton("_", "#4F46E5");
        btnMin.setOnClickListener(v -> toggleMinimize());
        header.addView(btnMin, new LinearLayout.LayoutParams(dp(28), dp(28)));

        Button btnClose = createSmallButton("✕", "#EF4444");
        btnClose.setOnClickListener(v -> stopSelf());
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(dp(28), dp(28));
        closeParams.setMarginStart(dp(4));
        header.addView(btnClose, closeParams);

        root.addView(header);

        // --- Conteúdo (pode ser minimizado) ---
        contentLayout = new LinearLayout(this);
        contentLayout.setOrientation(LinearLayout.VERTICAL);

        tvStatus = new TextView(this);
        tvStatus.setText("Pronto (modo ROOT)");
        tvStatus.setTextColor(Color.parseColor("#A78BFA"));
        tvStatus.setTextSize(11);
        contentLayout.addView(tvStatus);

        editDigits = new EditText(this);
        editDigits.setHint("Cole os números aqui");
        editDigits.setHintTextColor(Color.GRAY);
        editDigits.setTextColor(Color.WHITE);
        editDigits.setTextSize(16);
        editDigits.setBackgroundColor(Color.parseColor("#33FFFFFF"));
        editDigits.setPadding(dp(8), dp(8), dp(8), dp(8));
        LinearLayout.LayoutParams editParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        editParams.topMargin = dp(6);
        contentLayout.addView(editDigits, editParams);

        // Linha: Colar + Limpar
        LinearLayout row1 = new LinearLayout(this);
        row1.setPadding(0, dp(6), 0, 0);
        Button btnPaste = createButton("COLAR", "#4F46E5");
        btnPaste.setOnClickListener(v -> {
            ClipboardManager cb = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cb != null && cb.hasPrimaryClip() && cb.getPrimaryClip() != null) {
                CharSequence text = cb.getPrimaryClip().getItemAt(0).getText();
                if (text != null) editDigits.setText(text);
            }
        });
        Button btnClear = createButton("LIMPAR", "#4B5563");
        btnClear.setOnClickListener(v -> editDigits.setText(""));
        row1.addView(btnPaste, new LinearLayout.LayoutParams(0, dp(36), 1));
        row1.addView(btnClear, new LinearLayout.LayoutParams(0, dp(36), 1));
        contentLayout.addView(row1);

        // Delay
        TextView tvDelay = new TextView(this);
        tvDelay.setText("Delay: " + currentDelay + "ms");
        tvDelay.setTextColor(Color.WHITE);
        tvDelay.setTextSize(11);
        tvDelay.setPadding(0, dp(6), 0, 0);
        contentLayout.addView(tvDelay);

        SeekBar seekDelay = new SeekBar(this);
        seekDelay.setMax(1400);
        seekDelay.setProgress(400);
        seekDelay.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int i, boolean b) {
                currentDelay = Math.max(100, i + 100);
                tvDelay.setText("Delay: " + currentDelay + "ms");
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        contentLayout.addView(seekDelay);

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setVisibility(View.GONE);
        contentLayout.addView(progressBar);

        // Linha: Digitar + Abrir App
        LinearLayout row2 = new LinearLayout(this);
        row2.setPadding(0, dp(6), 0, 0);
        btnSend = createButton("⌨ DIGITAR", "#7C3AED");
        btnSend.setOnClickListener(v -> prepareTyping());
        Button btnOpenApp = createButton("APP", "#1E40AF");
        btnOpenApp.setOnClickListener(v -> {
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        });
        row2.addView(btnSend, new LinearLayout.LayoutParams(0, dp(44), 4));
        row2.addView(btnOpenApp, new LinearLayout.LayoutParams(0, dp(44), 1));
        contentLayout.addView(row2);

        root.addView(contentLayout);
        floatingView = root;

        // Parâmetros da janela overlay
        params = new WindowManager.LayoutParams(
                dp(270),
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
                        WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 80;
        params.y = 180;

        windowManager.addView(floatingView, params);

        // Arrastar pelo header
        header.setOnTouchListener(new View.OnTouchListener() {
            private int initialX, initialY;
            private float initialTouchX, initialTouchY;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
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

    private void toggleMinimize() {
        isMinimized = !isMinimized;
        contentLayout.setVisibility(isMinimized ? View.GONE : View.VISIBLE);
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

    private Button createSmallButton(String text, String color) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(10);
        b.setPadding(0, 0, 0, 0);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(Color.parseColor(color));
        gd.setCornerRadius(dp(6));
        b.setBackground(gd);
        return b;
    }

    private void prepareTyping() {
        String digits = editDigits.getText().toString().replaceAll("[^0-9*#]", "");
        if (digits.isEmpty()) {
            Toast.makeText(this, "Cole os números primeiro!", Toast.LENGTH_SHORT).show();
            return;
        }

        // Remover foco do widget para que os keyevents vão para o app em foco
        params.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        windowManager.updateViewLayout(floatingView, params);

        isPlaying = true;
        btnSend.setEnabled(false);
        tvStatus.setText("Aguardando 2s... TOQUE NO TECLADO!");
        tvStatus.setTextColor(Color.YELLOW);

        handler.postDelayed(() -> {
            if (isPlaying) startTyping(digits);
        }, 2000);
    }

    private void startTyping(String digits) {
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setMax(digits.length());
        tvStatus.setTextColor(Color.GREEN);

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
                    } catch (Exception ignored) {}
                }

                tvStatus.setText("Digitando: " + c + " (" + (index + 1) + "/" + chars.length + ")");
                progressBar.setProgress(index + 1);

                if (index == chars.length - 1) {
                    finishSequence();
                }
            }, (long) i * currentDelay);
        }
    }

    private void finishSequence() {
        isPlaying = false;
        handler.post(() -> {
            btnSend.setEnabled(true);
            progressBar.setVisibility(View.GONE);
            tvStatus.setText("Concluído!");
            tvStatus.setTextColor(Color.parseColor("#A78BFA"));

            // Devolver foco ao widget
            params.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            windowManager.updateViewLayout(floatingView, params);
        });
    }

    private int dp(int dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, getResources().getDisplayMetrics());
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "DTMF Widget", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Widget flutuante do Carioca Dialer");
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private Notification createNotification() {
        Intent stopIntent = new Intent(this, FloatingWidgetService.class);
        stopIntent.setAction("STOP");
        PendingIntent stopPending = PendingIntent.getService(this, 0, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        return builder
                .setContentTitle("Carioca Dialer — Widget Ativo")
                .setContentText("Widget flutuante em execução. Toque para fechar.")
                .setSmallIcon(android.R.drawable.ic_menu_call)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Fechar Widget", stopPending)
                .build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP".equals(intent.getAction())) {
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        if (floatingView != null && windowManager != null) {
            try {
                windowManager.removeView(floatingView);
            } catch (Exception ignored) {}
        }
    }
}
