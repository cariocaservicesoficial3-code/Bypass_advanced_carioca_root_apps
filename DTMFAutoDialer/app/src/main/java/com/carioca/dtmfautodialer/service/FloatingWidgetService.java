package com.carioca.dtmfautodialer.service;

import android.animation.ValueAnimator;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.IBinder;
import android.os.Vibrator;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.carioca.dtmfautodialer.hooks.MainHook;
import com.carioca.dtmfautodialer.ui.MainActivity;

/**
 * FloatingWidgetService - Widget flutuante que aparece sobre o app de telefone
 *
 * Permite ao usuário:
 * 1. Colar uma sequência de números (CPF, telefone, etc.)
 * 2. Configurar o delay entre dígitos
 * 3. Iniciar a digitação automática DTMF durante a ligação
 * 4. Acompanhar o progresso em tempo real
 * 5. Parar a sequência a qualquer momento
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

    // Componentes da UI
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
    private int currentDelay = 300; // ms
    private boolean isCallActive = false;

    // Receivers
    private BroadcastReceiver progressReceiver;
    private BroadcastReceiver completeReceiver;
    private BroadcastReceiver callStateReceiver;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createNotification());
        createFloatingWidget();
        registerReceivers();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.hasExtra("digits")) {
            String digits = intent.getStringExtra("digits");
            if (editDigits != null && digits != null) {
                editDigits.setText(digits);
            }
        }
        return START_STICKY;
    }

    /**
     * Cria o widget flutuante com design moderno
     */
    private void createFloatingWidget() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        // Layout principal
        LinearLayout mainLayout = new LinearLayout(this);
        mainLayout.setOrientation(LinearLayout.VERTICAL);

        // ============================================================
        // MINIMIZED VIEW (bolinha flutuante)
        // ============================================================
        minimizedView = createMinimizedView();
        mainLayout.addView(minimizedView);

        // ============================================================
        // EXPANDED VIEW (painel completo)
        // ============================================================
        expandedView = createExpandedView();
        expandedView.setVisibility(View.GONE);
        mainLayout.addView(expandedView);

        floatingView = mainLayout;

        // Parâmetros da janela flutuante
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

        // Adicionar drag para mover o widget
        setupDragListener(params);
    }

    /**
     * Cria a view minimizada (bolinha flutuante com ícone)
     */
    private View createMinimizedView() {
        LinearLayout miniLayout = new LinearLayout(this);
        miniLayout.setOrientation(LinearLayout.HORIZONTAL);
        miniLayout.setGravity(Gravity.CENTER);
        miniLayout.setPadding(dp(12), dp(12), dp(12), dp(12));

        // Background circular com gradiente
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColors(new int[]{Color.parseColor("#7C3AED"), Color.parseColor("#4F46E5")});
        bg.setOrientation(GradientDrawable.Orientation.TL_BR);
        bg.setStroke(dp(2), Color.parseColor("#A78BFA"));
        miniLayout.setBackground(bg);

        // Texto do ícone
        TextView icon = new TextView(this);
        icon.setText("#");
        icon.setTextColor(Color.WHITE);
        icon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        icon.setTypeface(null, android.graphics.Typeface.BOLD);
        miniLayout.addView(icon);

        // Badge de contagem
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

    /**
     * Cria a view expandida (painel de controle completo)
     */
    private View createExpandedView() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(12), dp(16), dp(16));

        // Background do painel
        GradientDrawable panelBg = new GradientDrawable();
        panelBg.setCornerRadius(dp(20));
        panelBg.setColor(Color.parseColor("#1E1B2E"));
        panelBg.setStroke(dp(1), Color.parseColor("#7C3AED"));
        panel.setBackground(panelBg);

        // ---- Header ----
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("DTMF Auto Dialer");
        title.setTextColor(Color.parseColor("#A78BFA"));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        title.setLayoutParams(titleLp);
        header.addView(title);

        // Botão minimizar
        TextView btnMinimize = new TextView(this);
        btnMinimize.setText("—");
        btnMinimize.setTextColor(Color.WHITE);
        btnMinimize.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        btnMinimize.setPadding(dp(12), dp(4), dp(4), dp(4));
        btnMinimize.setOnClickListener(v -> toggleExpanded());
        header.addView(btnMinimize);

        // Botão fechar
        TextView btnClose = new TextView(this);
        btnClose.setText("X");
        btnClose.setTextColor(Color.parseColor("#EF4444"));
        btnClose.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        btnClose.setPadding(dp(12), dp(4), dp(4), dp(4));
        btnClose.setOnClickListener(v -> stopSelf());
        header.addView(btnClose);

        panel.addView(header);

        // ---- Status da chamada ----
        tvStatus = new TextView(this);
        tvStatus.setText("Aguardando chamada...");
        tvStatus.setTextColor(Color.parseColor("#FBBF24"));
        tvStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tvStatus.setPadding(0, dp(8), 0, dp(8));
        panel.addView(tvStatus);

        // ---- Campo de entrada de dígitos ----
        TextView labelDigits = new TextView(this);
        labelDigits.setText("Cole os números aqui:");
        labelDigits.setTextColor(Color.parseColor("#D1D5DB"));
        labelDigits.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        panel.addView(labelDigits);

        editDigits = new EditText(this);
        editDigits.setHint("Ex: 072.818.591-12 ou 07281859112");
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

        LinearLayout.LayoutParams editLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        editLp.topMargin = dp(4);
        editDigits.setLayoutParams(editLp);
        panel.addView(editDigits);

        // Contador de dígitos
        final TextView tvDigitCount = new TextView(this);
        tvDigitCount.setTextColor(Color.parseColor("#9CA3AF"));
        tvDigitCount.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tvDigitCount.setPadding(0, dp(2), 0, 0);
        panel.addView(tvDigitCount);

        editDigits.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                String clean = s.toString().replaceAll("[^0-9*#]", "");
                tvDigitCount.setText(clean.length() + " dígitos DTMF válidos");
            }
        });

        // ---- Botão Colar ----
        btnPaste = createStyledButton("COLAR DA ÁREA DE TRANSFERÊNCIA", "#6366F1");
        LinearLayout.LayoutParams pasteLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(42));
        pasteLp.topMargin = dp(8);
        btnPaste.setLayoutParams(pasteLp);
        btnPaste.setOnClickListener(v -> pasteFromClipboard());
        panel.addView(btnPaste);

        // ---- Configuração de delay ----
        LinearLayout delayLayout = new LinearLayout(this);
        delayLayout.setOrientation(LinearLayout.HORIZONTAL);
        delayLayout.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams delayLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        delayLp.topMargin = dp(12);
        delayLayout.setLayoutParams(delayLp);

        TextView labelDelay = new TextView(this);
        labelDelay.setText("Delay: ");
        labelDelay.setTextColor(Color.parseColor("#D1D5DB"));
        labelDelay.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        delayLayout.addView(labelDelay);

        tvDelay = new TextView(this);
        tvDelay.setText("300ms");
        tvDelay.setTextColor(Color.parseColor("#A78BFA"));
        tvDelay.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tvDelay.setTypeface(null, android.graphics.Typeface.BOLD);
        delayLayout.addView(tvDelay);

        panel.addView(delayLayout);

        seekDelay = new SeekBar(this);
        seekDelay.setMax(900); // 100ms a 1000ms
        seekDelay.setProgress(200); // 300ms default (100 + 200)
        seekDelay.setPadding(0, dp(4), 0, dp(4));
        seekDelay.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                currentDelay = progress + 100;
                tvDelay.setText(currentDelay + "ms");
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        panel.addView(seekDelay);

        // ---- Barra de progresso ----
        tvProgress = new TextView(this);
        tvProgress.setTextColor(Color.parseColor("#10B981"));
        tvProgress.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tvProgress.setVisibility(View.GONE);
        LinearLayout.LayoutParams progTextLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        progTextLp.topMargin = dp(8);
        tvProgress.setLayoutParams(progTextLp);
        panel.addView(tvProgress);

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams progBarLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(8));
        progBarLp.topMargin = dp(4);
        progressBar.setLayoutParams(progBarLp);
        panel.addView(progressBar);

        // ---- Botões de ação ----
        LinearLayout actionLayout = new LinearLayout(this);
        actionLayout.setOrientation(LinearLayout.HORIZONTAL);
        actionLayout.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams actionLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        actionLp.topMargin = dp(12);
        actionLayout.setLayoutParams(actionLp);

        // Botão ENVIAR
        btnSend = createStyledButton("DIGITAR", "#7C3AED");
        LinearLayout.LayoutParams sendLp = new LinearLayout.LayoutParams(0, dp(48), 1);
        sendLp.rightMargin = dp(6);
        btnSend.setLayoutParams(sendLp);
        btnSend.setOnClickListener(v -> sendDtmfSequence());
        actionLayout.addView(btnSend);

        // Botão PARAR
        btnStop = createStyledButton("PARAR", "#EF4444");
        LinearLayout.LayoutParams stopLp = new LinearLayout.LayoutParams(0, dp(48), 1);
        stopLp.leftMargin = dp(6);
        btnStop.setLayoutParams(stopLp);
        btnStop.setEnabled(false);
        btnStop.setAlpha(0.5f);
        btnStop.setOnClickListener(v -> stopDtmfSequence());
        actionLayout.addView(btnStop);

        panel.addView(actionLayout);

        // ---- Créditos ----
        TextView credits = new TextView(this);
        credits.setText("Carioca Services - DTMF Auto Dialer v1.0");
        credits.setTextColor(Color.parseColor("#4B5563"));
        credits.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        credits.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams creditsLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        creditsLp.topMargin = dp(8);
        credits.setLayoutParams(creditsLp);
        panel.addView(credits);

        // Definir tamanho do painel
        LinearLayout.LayoutParams panelLp = new LinearLayout.LayoutParams(dp(300), LinearLayout.LayoutParams.WRAP_CONTENT);
        panel.setLayoutParams(panelLp);

        scrollView.addView(panel);
        return scrollView;
    }

    /**
     * Cria um botão estilizado
     */
    private Button createStyledButton(String text, String colorHex) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        btn.setTypeface(null, android.graphics.Typeface.BOLD);
        btn.setAllCaps(false);

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        bg.setColor(Color.parseColor(colorHex));
        btn.setBackground(bg);
        btn.setPadding(dp(16), dp(8), dp(16), dp(8));

        return btn;
    }

    /**
     * Alterna entre view minimizada e expandida
     */
    private void toggleExpanded() {
        isExpanded = !isExpanded;

        if (isExpanded) {
            minimizedView.setVisibility(View.GONE);
            expandedView.setVisibility(View.VISIBLE);

            // Tornar focável quando expandido (para poder digitar)
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) floatingView.getLayoutParams();
            params.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
            params.width = WindowManager.LayoutParams.WRAP_CONTENT;
            params.height = WindowManager.LayoutParams.WRAP_CONTENT;
            windowManager.updateViewLayout(floatingView, params);
        } else {
            expandedView.setVisibility(View.GONE);
            minimizedView.setVisibility(View.VISIBLE);

            // Voltar a ser não-focável quando minimizado
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) floatingView.getLayoutParams();
            params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            params.width = WindowManager.LayoutParams.WRAP_CONTENT;
            params.height = WindowManager.LayoutParams.WRAP_CONTENT;
            windowManager.updateViewLayout(floatingView, params);
        }
    }

    /**
     * Configura o listener de arrastar para mover o widget
     */
    private void setupDragListener(WindowManager.LayoutParams params) {
        final int[] lastTouchX = {0};
        final int[] lastTouchY = {0};
        final int[] initialX = {0};
        final int[] initialY = {0};
        final boolean[] isDragging = {false};

        minimizedView.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    lastTouchX[0] = (int) event.getRawX();
                    lastTouchY[0] = (int) event.getRawY();
                    initialX[0] = params.x;
                    initialY[0] = params.y;
                    isDragging[0] = false;
                    return true;

                case MotionEvent.ACTION_MOVE:
                    int dx = (int) event.getRawX() - lastTouchX[0];
                    int dy = (int) event.getRawY() - lastTouchY[0];

                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                        isDragging[0] = true;
                    }

                    params.x = initialX[0] + dx;
                    params.y = initialY[0] + dy;
                    windowManager.updateViewLayout(floatingView, params);
                    return true;

                case MotionEvent.ACTION_UP:
                    if (!isDragging[0]) {
                        toggleExpanded();
                    }
                    return true;
            }
            return false;
        });
    }

    /**
     * Cola texto da área de transferência
     */
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

                        // Vibrar feedback
                        Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
                        if (vibrator != null) {
                            vibrator.vibrate(50);
                        }

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
     * Envia a sequência DTMF via broadcast para o hook
     */
    private void sendDtmfSequence() {
        String digits = editDigits.getText().toString().trim();
        if (digits.isEmpty()) {
            Toast.makeText(this, "Cole ou digite os números primeiro!", Toast.LENGTH_SHORT).show();
            return;
        }

        // Limpar - manter apenas dígitos DTMF válidos
        String cleanDigits = digits.replaceAll("[^0-9*#]", "");
        if (cleanDigits.isEmpty()) {
            Toast.makeText(this, "Nenhum dígito válido encontrado!", Toast.LENGTH_SHORT).show();
            return;
        }

        // Enviar broadcast para o hook
        Intent intent = new Intent(MainHook.ACTION_SEND_DTMF_SEQUENCE);
        intent.putExtra(MainHook.EXTRA_DIGITS, cleanDigits);
        intent.putExtra(MainHook.EXTRA_DELAY_MS, currentDelay);
        sendBroadcast(intent);

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

        tvStatus.setText("Digitando DTMF...");
        tvStatus.setTextColor(Color.parseColor("#10B981"));

        // Vibrar
        Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator != null) {
            vibrator.vibrate(100);
        }
    }

    /**
     * Para a sequência DTMF
     */
    private void stopDtmfSequence() {
        Intent intent = new Intent(MainHook.ACTION_STOP_DTMF);
        sendBroadcast(intent);

        resetUI();
        tvStatus.setText("Sequência interrompida");
        tvStatus.setTextColor(Color.parseColor("#EF4444"));
    }

    /**
     * Reseta a UI para o estado inicial
     */
    private void resetUI() {
        btnSend.setEnabled(true);
        btnSend.setAlpha(1.0f);
        btnStop.setEnabled(false);
        btnStop.setAlpha(0.5f);
        progressBar.setVisibility(View.GONE);
        tvProgress.setVisibility(View.GONE);
    }

    /**
     * Registra receivers para progresso e estado da chamada
     */
    private void registerReceivers() {
        // Receiver de progresso
        progressReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                int current = intent.getIntExtra("current", 0);
                int total = intent.getIntExtra("total", 0);
                String digit = intent.getStringExtra("digit");

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
        };

        // Receiver de conclusão
        completeReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                resetUI();
                tvStatus.setText("Sequência concluída!");
                tvStatus.setTextColor(Color.parseColor("#10B981"));

                if (tvMiniBadge != null) {
                    tvMiniBadge.setText("OK");
                }

                // Vibrar conclusão
                Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
                if (vibrator != null) {
                    vibrator.vibrate(new long[]{0, 100, 100, 100}, -1);
                }

                Toast.makeText(FloatingWidgetService.this,
                        "Sequência DTMF concluída!", Toast.LENGTH_SHORT).show();
            }
        };

        // Receiver de estado da chamada
        callStateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                isCallActive = intent.getBooleanExtra(MainHook.EXTRA_CALL_ACTIVE, false);
                updateCallStateUI();
            }
        };

        IntentFilter progressFilter = new IntentFilter("com.carioca.dtmfautodialer.DTMF_PROGRESS");
        IntentFilter completeFilter = new IntentFilter("com.carioca.dtmfautodialer.DTMF_COMPLETE");
        IntentFilter callStateFilter = new IntentFilter(MainHook.ACTION_CALL_STATE_CHANGED);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(progressReceiver, progressFilter, Context.RECEIVER_EXPORTED);
            registerReceiver(completeReceiver, completeFilter, Context.RECEIVER_EXPORTED);
            registerReceiver(callStateReceiver, callStateFilter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(progressReceiver, progressFilter);
            registerReceiver(completeReceiver, completeFilter);
            registerReceiver(callStateReceiver, callStateFilter);
        }
    }

    /**
     * Atualiza a UI com base no estado da chamada
     */
    private void updateCallStateUI() {
        if (tvStatus != null) {
            if (isCallActive) {
                tvStatus.setText("Chamada ativa - Pronto para digitar!");
                tvStatus.setTextColor(Color.parseColor("#10B981"));
            } else {
                tvStatus.setText("Aguardando chamada...");
                tvStatus.setTextColor(Color.parseColor("#FBBF24"));
            }
        }
    }

    /**
     * Cria o canal de notificação
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "DTMF Auto Dialer",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Serviço de digitação automática DTMF");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    /**
     * Cria a notificação do serviço foreground
     */
    private Notification createNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        return builder
                .setContentTitle("DTMF Auto Dialer")
                .setContentText("Widget flutuante ativo - toque para abrir")
                .setSmallIcon(android.R.drawable.ic_menu_call)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    /**
     * Converte dp para pixels
     */
    private int dp(int dp) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp,
                getResources().getDisplayMetrics()
        );
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        if (floatingView != null && windowManager != null) {
            windowManager.removeView(floatingView);
        }

        try {
            if (progressReceiver != null) unregisterReceiver(progressReceiver);
            if (completeReceiver != null) unregisterReceiver(completeReceiver);
            if (callStateReceiver != null) unregisterReceiver(callStateReceiver);
        } catch (Exception e) {
            // Ignorar
        }
    }
}
