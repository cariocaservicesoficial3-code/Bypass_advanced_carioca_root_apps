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
import android.text.Editable;
import android.text.TextWatcher;
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

import com.carioca.dtmfautodialer.hooks.MainHook;
import com.carioca.dtmfautodialer.ui.MainActivity;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;

/**
 * FloatingWidgetService - Widget flutuante para DTMF Auto Dialer v1.2
 *
 * Comunicação com o hook via arquivo compartilhado:
 * - Escreve comandos em /sdcard/Android/data/.dtmf_autodialer/command.txt
 * - Lê status de /sdcard/Android/data/.dtmf_autodialer/status.txt via polling
 */
public class FloatingWidgetService extends Service {

    private static final String TAG = "DTMFAutoDialer";
    private static final String CHANNEL_ID = "dtmf_auto_dialer_channel";
    private static final int NOTIFICATION_ID = 1337;

    private WindowManager windowManager;
    private View floatingView;
    private View expandedView;
    private View minimizedView;
    private boolean isExpanded = false;

    // UI Components
    private EditText editDigits;
    private TextView tvStatus;
    private TextView tvProgress;
    private ProgressBar progressBar;
    private Button btnSend;
    private Button btnStop;
    private Button btnPaste;
    private SeekBar seekDelay;
    private TextView tvDelay;
    private TextView tvMiniBadge;

    // Estado
    private int currentDelay = 300;
    private boolean isCallActive = false;

    // Polling do status
    private Handler statusHandler = new Handler(Looper.getMainLooper());
    private Runnable statusPoller;
    private long lastStatusTimestamp = 0;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createNotification());
        ensureCommandDir();
        createFloatingWidget();
        startStatusPolling();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.hasExtra("digits")) {
            String digits = intent.getStringExtra("digits");
            if (editDigits != null && digits != null) {
                editDigits.setText(digits);
            }
        }

        // Carregar delay das preferências
        SharedPreferences prefs = getSharedPreferences("dtmf_prefs", MODE_PRIVATE);
        currentDelay = prefs.getInt("delay_ms", 300);

        return START_STICKY;
    }

    /**
     * Vibra com segurança
     */
    private void safeVibrate(long ms) {
        try {
            android.os.Vibrator v = (android.os.Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null && v.hasVibrator()) v.vibrate(ms);
        } catch (Exception e) { /* ignorar */ }
    }

    // ============================================================
    // COMUNICAÇÃO VIA ARQUIVO
    // ============================================================

    private void ensureCommandDir() {
        try {
            File dir = new File(MainHook.COMMAND_DIR);
            if (!dir.exists()) {
                // Tentar criar via shell com su para garantir permissões
                try {
                    Process p = Runtime.getRuntime().exec(new String[]{"su", "-c",
                            "mkdir -p " + MainHook.COMMAND_DIR + " && chmod 777 " + MainHook.COMMAND_DIR});
                    p.waitFor();
                } catch (Exception e) {
                    // Fallback: criar normalmente
                    dir.mkdirs();
                }
            }
            File cmd = new File(MainHook.COMMAND_FILE);
            if (!cmd.exists()) {
                try {
                    Process p = Runtime.getRuntime().exec(new String[]{"su", "-c",
                            "touch " + MainHook.COMMAND_FILE + " && chmod 666 " + MainHook.COMMAND_FILE});
                    p.waitFor();
                } catch (Exception e) {
                    cmd.createNewFile();
                }
            }
            File status = new File(MainHook.STATUS_FILE);
            if (!status.exists()) {
                try {
                    Process p = Runtime.getRuntime().exec(new String[]{"su", "-c",
                            "touch " + MainHook.STATUS_FILE + " && chmod 666 " + MainHook.STATUS_FILE});
                    p.waitFor();
                } catch (Exception e) {
                    status.createNewFile();
                }
            }
        } catch (Exception e) {
            // Ignorar
        }
    }

    /**
     * Escreve comando para o hook ler
     * Formato: SEND|dígitos|delay_ms  ou  STOP
     */
    private void writeCommand(String command) {
        ensureCommandDir();
        // Tentar escrita direta primeiro
        try {
            FileWriter writer = new FileWriter(MainHook.COMMAND_FILE, false);
            writer.write(command);
            writer.flush();
            writer.close();
            return; // Sucesso!
        } catch (Exception e) {
            // Escrita direta falhou, tentar via su
        }
        // Fallback: escrever via su (root)
        try {
            String escaped = command.replace("\"", "\\\"");
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c",
                    "echo '" + escaped + "' > " + MainHook.COMMAND_FILE + " && chmod 666 " + MainHook.COMMAND_FILE});
            p.waitFor();
        } catch (Exception e2) {
            Toast.makeText(this, "Erro ao enviar comando: " + e2.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Lê o status escrito pelo hook
     */
    private String readStatus() {
        try {
            File statusFile = new File(MainHook.STATUS_FILE);
            if (!statusFile.exists() || statusFile.length() == 0) return null;

            BufferedReader reader = new BufferedReader(new FileReader(statusFile));
            String statusLine = reader.readLine();
            String timestampLine = reader.readLine();
            reader.close();

            if (statusLine == null) return null;

            // Verificar se é um status novo
            if (timestampLine != null) {
                try {
                    long ts = Long.parseLong(timestampLine.trim());
                    if (ts <= lastStatusTimestamp) return null; // Já processado
                    lastStatusTimestamp = ts;
                } catch (NumberFormatException e) {
                    // Ignorar
                }
            }

            return statusLine;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Inicia polling do arquivo de status a cada 300ms
     */
    private void startStatusPolling() {
        statusPoller = new Runnable() {
            @Override
            public void run() {
                String status = readStatus();
                if (status != null && !status.isEmpty()) {
                    processStatus(status);
                }
                statusHandler.postDelayed(this, 300);
            }
        };
        statusHandler.postDelayed(statusPoller, 300);
    }

    /**
     * Processa o status recebido do hook
     */
    private void processStatus(String status) {
        try {
            String[] parts = status.split("\\|");
            String action = parts[0].trim();

            switch (action) {
                case "CALL_ACTIVE":
                    isCallActive = true;
                    if (tvStatus != null) {
                        tvStatus.setText("Chamada ativa - Pronto!");
                        tvStatus.setTextColor(Color.parseColor("#10B981"));
                    }
                    break;

                case "CALL_ENDED":
                    isCallActive = false;
                    if (tvStatus != null) {
                        tvStatus.setText("Chamada encerrada");
                        tvStatus.setTextColor(Color.parseColor("#EF4444"));
                    }
                    resetUI();
                    break;

                case "CALL_RINGING":
                    if (tvStatus != null) {
                        tvStatus.setText("Chamada tocando...");
                        tvStatus.setTextColor(Color.parseColor("#FBBF24"));
                    }
                    break;

                case "PROGRESS":
                    if (parts.length >= 4) {
                        int current = Integer.parseInt(parts[1].trim());
                        int total = Integer.parseInt(parts[2].trim());
                        String digit = parts[3].trim();

                        if (progressBar != null) {
                            progressBar.setMax(total);
                            progressBar.setProgress(current);
                        }
                        if (tvProgress != null) {
                            tvProgress.setText("Digitando: " + current + "/" + total + " [" + digit + "]");
                        }
                        if (tvMiniBadge != null) {
                            tvMiniBadge.setVisibility(View.VISIBLE);
                            tvMiniBadge.setText(current + "/" + total);
                        }
                    }
                    break;

                case "PLAYING":
                    if (tvStatus != null) {
                        tvStatus.setText("Digitando DTMF...");
                        tvStatus.setTextColor(Color.parseColor("#10B981"));
                    }
                    break;

                case "COMPLETE":
                    resetUI();
                    if (tvStatus != null) {
                        tvStatus.setText("Sequência concluída!");
                        tvStatus.setTextColor(Color.parseColor("#10B981"));
                    }
                    if (tvMiniBadge != null) {
                        tvMiniBadge.setText("OK");
                    }
                    safeVibrate(200);
                    Toast.makeText(this, "DTMF concluído!", Toast.LENGTH_SHORT).show();
                    break;

                case "STOPPED":
                    resetUI();
                    if (tvStatus != null) {
                        tvStatus.setText("Sequência interrompida");
                        tvStatus.setTextColor(Color.parseColor("#FBBF24"));
                    }
                    break;

                case "ERROR":
                    resetUI();
                    String errorMsg = parts.length > 1 ? parts[1].trim() : "Erro desconhecido";
                    if (tvStatus != null) {
                        tvStatus.setText("Erro: " + errorMsg);
                        tvStatus.setTextColor(Color.parseColor("#EF4444"));
                    }
                    Toast.makeText(this, errorMsg, Toast.LENGTH_LONG).show();
                    break;
            }
        } catch (Exception e) {
            // Ignorar erros de parsing
        }
    }

    // ============================================================
    // UI DO WIDGET FLUTUANTE
    // ============================================================

    private void createFloatingWidget() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        LinearLayout mainLayout = new LinearLayout(this);
        mainLayout.setOrientation(LinearLayout.VERTICAL);

        minimizedView = createMinimizedView();
        mainLayout.addView(minimizedView);

        expandedView = createExpandedView();
        expandedView.setVisibility(View.GONE);
        mainLayout.addView(expandedView);

        floatingView = mainLayout;

        int layoutType;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            layoutType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            layoutType = WindowManager.LayoutParams.TYPE_PHONE;
        }

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 50;
        params.y = 300;

        windowManager.addView(floatingView, params);
        setupDragListener(params);
    }

    private View createMinimizedView() {
        LinearLayout miniLayout = new LinearLayout(this);
        miniLayout.setOrientation(LinearLayout.HORIZONTAL);
        miniLayout.setGravity(Gravity.CENTER);
        miniLayout.setPadding(dp(12), dp(12), dp(12), dp(12));

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColors(new int[]{Color.parseColor("#7C3AED"), Color.parseColor("#4F46E5")});
        bg.setOrientation(GradientDrawable.Orientation.TL_BR);
        bg.setStroke(dp(2), Color.parseColor("#A78BFA"));
        miniLayout.setBackground(bg);

        TextView icon = new TextView(this);
        icon.setText("#");
        icon.setTextColor(Color.WHITE);
        icon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        icon.setTypeface(null, Typeface.BOLD);
        miniLayout.addView(icon);

        tvMiniBadge = new TextView(this);
        tvMiniBadge.setTextColor(Color.parseColor("#FCD34D"));
        tvMiniBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        tvMiniBadge.setVisibility(View.GONE);
        miniLayout.addView(tvMiniBadge);

        miniLayout.setOnClickListener(v -> toggleExpanded());

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(56), dp(56));
        miniLayout.setLayoutParams(lp);

        return miniLayout;
    }

    private View createExpandedView() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(12), dp(16), dp(16));

        GradientDrawable panelBg = new GradientDrawable();
        panelBg.setCornerRadius(dp(20));
        panelBg.setColor(Color.parseColor("#1E1B2E"));
        panelBg.setStroke(dp(1), Color.parseColor("#7C3AED"));
        panel.setBackground(panelBg);

        // Header
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("DTMF Auto Dialer");
        title.setTextColor(Color.parseColor("#A78BFA"));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        title.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        title.setLayoutParams(titleLp);
        header.addView(title);

        TextView btnMinimize = new TextView(this);
        btnMinimize.setText("—");
        btnMinimize.setTextColor(Color.WHITE);
        btnMinimize.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        btnMinimize.setPadding(dp(12), dp(4), dp(4), dp(4));
        btnMinimize.setOnClickListener(v -> toggleExpanded());
        header.addView(btnMinimize);

        TextView btnClose = new TextView(this);
        btnClose.setText("X");
        btnClose.setTextColor(Color.parseColor("#EF4444"));
        btnClose.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        btnClose.setPadding(dp(12), dp(4), dp(4), dp(4));
        btnClose.setOnClickListener(v -> stopSelf());
        header.addView(btnClose);

        panel.addView(header);

        // Status
        tvStatus = new TextView(this);
        tvStatus.setText("Aguardando chamada...");
        tvStatus.setTextColor(Color.parseColor("#FBBF24"));
        tvStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tvStatus.setPadding(0, dp(8), 0, dp(8));
        panel.addView(tvStatus);

        // Label
        TextView labelDigits = new TextView(this);
        labelDigits.setText("Cole os números aqui:");
        labelDigits.setTextColor(Color.parseColor("#D1D5DB"));
        labelDigits.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        panel.addView(labelDigits);

        // EditText para dígitos
        editDigits = new EditText(this);
        editDigits.setHint("Ex: 072.818.591-12");
        editDigits.setHintTextColor(Color.parseColor("#6B7280"));
        editDigits.setTextColor(Color.WHITE);
        editDigits.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        editDigits.setSingleLine(false);
        editDigits.setMaxLines(3);
        editDigits.setPadding(dp(12), dp(10), dp(12), dp(10));

        GradientDrawable editBg = new GradientDrawable();
        editBg.setCornerRadius(dp(12));
        editBg.setColor(Color.parseColor("#2D2A3E"));
        editBg.setStroke(dp(1), Color.parseColor("#4B5563"));
        editDigits.setBackground(editBg);

        editDigits.setOnFocusChangeListener((v, hasFocus) -> {
            WindowManager.LayoutParams lp = (WindowManager.LayoutParams) floatingView.getLayoutParams();
            if (hasFocus) {
                lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            } else {
                lp.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            }
            try { windowManager.updateViewLayout(floatingView, lp); } catch (Exception e) { }
        });

        LinearLayout.LayoutParams editLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        editLp.topMargin = dp(6);
        editDigits.setLayoutParams(editLp);
        panel.addView(editDigits);

        // Preview
        final TextView tvPreview = new TextView(this);
        tvPreview.setTextColor(Color.parseColor("#9CA3AF"));
        tvPreview.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tvPreview.setPadding(0, dp(4), 0, 0);
        tvPreview.setVisibility(View.GONE);
        panel.addView(tvPreview);

        editDigits.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                String clean = s.toString().replaceAll("[^0-9*#]", "");
                if (!clean.isEmpty()) {
                    tvPreview.setVisibility(View.VISIBLE);
                    tvPreview.setText("Dígitos: " + clean + " (" + clean.length() + " dígitos)");
                } else {
                    tvPreview.setVisibility(View.GONE);
                }
            }
        });

        // Botão Colar
        btnPaste = createStyledButton("COLAR", "#6D28D9");
        btnPaste.setOnClickListener(v -> pasteFromClipboard());
        LinearLayout.LayoutParams pasteLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(40));
        pasteLp.topMargin = dp(8);
        btnPaste.setLayoutParams(pasteLp);
        panel.addView(btnPaste);

        // Delay
        LinearLayout delayLayout = new LinearLayout(this);
        delayLayout.setOrientation(LinearLayout.HORIZONTAL);
        delayLayout.setGravity(Gravity.CENTER_VERTICAL);
        delayLayout.setPadding(0, dp(10), 0, 0);

        TextView labelDelay = new TextView(this);
        labelDelay.setText("Delay: ");
        labelDelay.setTextColor(Color.parseColor("#D1D5DB"));
        labelDelay.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        delayLayout.addView(labelDelay);

        tvDelay = new TextView(this);
        tvDelay.setText(currentDelay + "ms");
        tvDelay.setTextColor(Color.parseColor("#A78BFA"));
        tvDelay.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tvDelay.setTypeface(null, Typeface.BOLD);
        delayLayout.addView(tvDelay);

        panel.addView(delayLayout);

        seekDelay = new SeekBar(this);
        seekDelay.setMax(900);
        seekDelay.setProgress(currentDelay - 100);
        seekDelay.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                currentDelay = progress + 100;
                tvDelay.setText(currentDelay + "ms");
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                SharedPreferences prefs = getSharedPreferences("dtmf_prefs", MODE_PRIVATE);
                prefs.edit().putInt("delay_ms", currentDelay).apply();
            }
        });
        panel.addView(seekDelay);

        // Progress bar
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(8));
        progressLp.topMargin = dp(8);
        progressBar.setLayoutParams(progressLp);
        panel.addView(progressBar);

        tvProgress = new TextView(this);
        tvProgress.setTextColor(Color.parseColor("#10B981"));
        tvProgress.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tvProgress.setVisibility(View.GONE);
        tvProgress.setGravity(Gravity.CENTER);
        panel.addView(tvProgress);

        // Botões de ação
        LinearLayout btnLayout = new LinearLayout(this);
        btnLayout.setOrientation(LinearLayout.HORIZONTAL);
        btnLayout.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams btnContainerLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        btnContainerLp.topMargin = dp(10);
        btnLayout.setLayoutParams(btnContainerLp);

        btnSend = createStyledButton("DIGITAR", "#7C3AED");
        btnSend.setOnClickListener(v -> sendDtmfSequence());
        LinearLayout.LayoutParams sendLp = new LinearLayout.LayoutParams(0, dp(44), 1);
        sendLp.rightMargin = dp(4);
        btnSend.setLayoutParams(sendLp);
        btnLayout.addView(btnSend);

        btnStop = createStyledButton("PARAR", "#EF4444");
        btnStop.setEnabled(false);
        btnStop.setAlpha(0.5f);
        btnStop.setOnClickListener(v -> stopDtmfSequence());
        LinearLayout.LayoutParams stopLp = new LinearLayout.LayoutParams(0, dp(44), 1);
        stopLp.leftMargin = dp(4);
        btnStop.setLayoutParams(stopLp);
        btnLayout.addView(btnStop);

        panel.addView(btnLayout);

        LinearLayout.LayoutParams panelLp = new LinearLayout.LayoutParams(dp(300), LinearLayout.LayoutParams.WRAP_CONTENT);
        panel.setLayoutParams(panelLp);

        scrollView.addView(panel);
        return scrollView;
    }

    private Button createStyledButton(String text, String color) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        btn.setTypeface(null, Typeface.BOLD);
        btn.setAllCaps(true);

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        bg.setColor(Color.parseColor(color));
        btn.setBackground(bg);
        btn.setPadding(dp(12), dp(8), dp(12), dp(8));
        return btn;
    }

    private void toggleExpanded() {
        isExpanded = !isExpanded;

        if (isExpanded) {
            minimizedView.setVisibility(View.GONE);
            expandedView.setVisibility(View.VISIBLE);
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) floatingView.getLayoutParams();
            params.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            params.width = WindowManager.LayoutParams.WRAP_CONTENT;
            params.height = WindowManager.LayoutParams.WRAP_CONTENT;
            try { windowManager.updateViewLayout(floatingView, params); } catch (Exception e) { }
        } else {
            minimizedView.setVisibility(View.VISIBLE);
            expandedView.setVisibility(View.GONE);
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) floatingView.getLayoutParams();
            params.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            params.width = WindowManager.LayoutParams.WRAP_CONTENT;
            params.height = WindowManager.LayoutParams.WRAP_CONTENT;
            try { windowManager.updateViewLayout(floatingView, params); } catch (Exception e) { }
        }
    }

    private void setupDragListener(WindowManager.LayoutParams params) {
        final int[] initialX = new int[1];
        final int[] initialY = new int[1];
        final float[] initialTouchX = new float[1];
        final float[] initialTouchY = new float[1];
        final boolean[] isDragging = new boolean[1];

        minimizedView.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    initialX[0] = params.x;
                    initialY[0] = params.y;
                    initialTouchX[0] = event.getRawX();
                    initialTouchY[0] = event.getRawY();
                    isDragging[0] = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    int dx = (int) (event.getRawX() - initialTouchX[0]);
                    int dy = (int) (event.getRawY() - initialTouchY[0]);
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) isDragging[0] = true;
                    params.x = initialX[0] + dx;
                    params.y = initialY[0] + dy;
                    try { windowManager.updateViewLayout(floatingView, params); } catch (Exception e) { }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!isDragging[0]) toggleExpanded();
                    return true;
            }
            return false;
        });
    }

    private void pasteFromClipboard() {
        try {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null && clipboard.hasPrimaryClip()) {
                ClipData clip = clipboard.getPrimaryClip();
                if (clip != null && clip.getItemCount() > 0) {
                    CharSequence text = clip.getItemAt(0).getText();
                    if (text != null) {
                        editDigits.setText(text);
                        editDigits.setSelection(editDigits.getText().length());
                        safeVibrate(50);
                        Toast.makeText(this, "Colado: " + text, Toast.LENGTH_SHORT).show();
                    }
                }
            } else {
                Toast.makeText(this, "Área de transferência vazia", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "Erro ao colar: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Envia sequência DTMF escrevendo comando no arquivo
     */
    private void sendDtmfSequence() {
        String digits = editDigits.getText().toString().trim();
        if (digits.isEmpty()) {
            Toast.makeText(this, "Cole ou digite os números primeiro!", Toast.LENGTH_SHORT).show();
            return;
        }

        String cleanDigits = digits.replaceAll("[^0-9*#]", "");
        if (cleanDigits.isEmpty()) {
            Toast.makeText(this, "Nenhum dígito válido!", Toast.LENGTH_SHORT).show();
            return;
        }

        // Escrever comando no arquivo para o hook ler
        writeCommand("SEND|" + cleanDigits + "|" + currentDelay);

        // Atualizar UI
        btnSend.setEnabled(false);
        btnSend.setAlpha(0.5f);
        btnStop.setEnabled(true);
        btnStop.setAlpha(1.0f);

        progressBar.setVisibility(View.VISIBLE);
        progressBar.setMax(cleanDigits.length());
        progressBar.setProgress(0);

        tvProgress.setVisibility(View.VISIBLE);
        tvProgress.setText("Enviando: 0/" + cleanDigits.length());

        tvStatus.setText("Comando enviado ao hook...");
        tvStatus.setTextColor(Color.parseColor("#10B981"));

        safeVibrate(100);
    }

    /**
     * Para a sequência DTMF
     */
    private void stopDtmfSequence() {
        writeCommand("STOP");
        resetUI();
        tvStatus.setText("Parando...");
        tvStatus.setTextColor(Color.parseColor("#FBBF24"));
    }

    private void resetUI() {
        if (btnSend != null) { btnSend.setEnabled(true); btnSend.setAlpha(1.0f); }
        if (btnStop != null) { btnStop.setEnabled(false); btnStop.setAlpha(0.5f); }
        if (progressBar != null) progressBar.setVisibility(View.GONE);
        if (tvProgress != null) tvProgress.setVisibility(View.GONE);
    }

    private int dp(int dp) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp, getResources().getDisplayMetrics());
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "DTMF Auto Dialer", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Serviço de digitação automática DTMF");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private Notification createNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        return builder
                .setContentTitle("DTMF Auto Dialer")
                .setContentText("Widget flutuante ativo")
                .setSmallIcon(android.R.drawable.ic_menu_call)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (statusHandler != null && statusPoller != null) {
            statusHandler.removeCallbacks(statusPoller);
        }
        if (floatingView != null && windowManager != null) {
            try { windowManager.removeView(floatingView); } catch (Exception e) { }
        }
    }
}
