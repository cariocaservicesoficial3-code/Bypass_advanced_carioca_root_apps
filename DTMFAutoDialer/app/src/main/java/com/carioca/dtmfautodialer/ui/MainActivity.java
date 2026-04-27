package com.carioca.dtmfautodialer.ui;

import android.app.role.RoleManager;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.telecom.Call;
import android.telecom.TelecomManager;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.carioca.dtmfautodialer.R;
import com.carioca.dtmfautodialer.service.CariocaInCallService;

/**
 * MainActivity v3.1 - TELA ÚNICA EVOLUÍDA
 * - Rediscagem (🔁)
 * - Automação Calcard (Ligar + 3s + "2")
 * - Teclado Retrátil
 * - Botão Desligar Dedicado
 */
public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_DEFAULT_DIALER = 123;
    private static final String PREFS_NAME = "CariocaPrefs";
    private static final String KEY_LAST_NUMBER = "last_number";
    public static final String EXTRA_GO_TO_INCALL = "go_to_incall";

    private TextView tvModuleStatus, tvIncallNumber, tvCallState, tvTypingStatus, tvDelayLabel;
    private Button btnSetDefault, btnCalcard, btnSpeaker, btnMainAction, btnToggleKeypad, btnHangupDedicated, btnClearDtmf;
    private EditText editPhoneNumber, editDtmfSequence;
    private LinearLayout layoutCallInfo, layoutDtmfProgress, layoutKeypadContainer;
    private ProgressBar progressDtmf;
    private SeekBar seekDelay;
    private GridLayout gridKeypad;

    private boolean isSpeakerOn = true;
    private boolean isTyping = false;
    private boolean isKeypadVisible = false;
    private boolean isCalcardAutomationPending = false;
    private int currentDelay = 500;
    private Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        
        initViews();
        setupKeypad();
        setupListeners();
        checkIfDefaultDialer();
        
        // Carregar último número
        String lastNum = prefs.getString(KEY_LAST_NUMBER, "");
        if (!lastNum.isEmpty()) editPhoneNumber.setText(lastNum);

        startCallStateUpdater();
    }

    private void initViews() {
        tvModuleStatus = findViewById(R.id.tv_module_status);
        tvIncallNumber = findViewById(R.id.tv_incall_number);
        tvCallState = findViewById(R.id.tv_call_state);
        tvTypingStatus = findViewById(R.id.tv_typing_status);
        tvDelayLabel = findViewById(R.id.tv_delay_label);
        
        btnSetDefault = findViewById(R.id.btn_set_default);
        btnCalcard = findViewById(R.id.btn_calcard_consulta);
        btnSpeaker = findViewById(R.id.btn_speaker);
        btnMainAction = findViewById(R.id.btn_main_action);
        btnToggleKeypad = findViewById(R.id.btn_toggle_keypad);
        btnHangupDedicated = findViewById(R.id.btn_hangup_dedicated);
        btnClearDtmf = findViewById(R.id.btn_clear_dtmf);
        
        editPhoneNumber = findViewById(R.id.edit_phone_number);
        editDtmfSequence = findViewById(R.id.edit_dtmf_sequence);
        
        layoutCallInfo = findViewById(R.id.layout_call_info);
        layoutDtmfProgress = findViewById(R.id.layout_dtmf_progress);
        layoutKeypadContainer = findViewById(R.id.layout_keypad_container);
        progressDtmf = findViewById(R.id.progress_dtmf);
        seekDelay = findViewById(R.id.seek_delay);
        gridKeypad = findViewById(R.id.grid_keypad);

        updateSpeakerUI();
    }

    private void setupKeypad() {
        for (int i = 0; i < gridKeypad.getChildCount(); i++) {
            View v = gridKeypad.getChildAt(i);
            if (v instanceof Button) {
                final String key = (String) v.getTag();
                v.setOnClickListener(view -> {
                    String current = editPhoneNumber.getText().toString();
                    editPhoneNumber.setText(current + key);
                    if (CariocaInCallService.currentCall != null) {
                        CariocaInCallService.sendDtmf(key.charAt(0));
                    }
                });
            }
        }
    }

    private void setupListeners() {
        btnSetDefault.setOnClickListener(v -> requestDefaultDialerRole());
        
        btnToggleKeypad.setOnClickListener(v -> {
            isKeypadVisible = !isKeypadVisible;
            layoutKeypadContainer.setVisibility(isKeypadVisible ? View.VISIBLE : View.GONE);
            btnToggleKeypad.setText(isKeypadVisible ? "✖" : "⌨");
        });

        btnCalcard.setOnClickListener(v -> {
            final String calcardNum = "08006484455";
            editPhoneNumber.setText(calcardNum);
            isCalcardAutomationPending = true;

            if (CariocaInCallService.currentCall != null) {
                // Matar chamada atual e religar
                CariocaInCallService.currentCall.disconnect();
                Toast.makeText(this, "Reiniciando chamada Calcard...", Toast.LENGTH_SHORT).show();
                handler.postDelayed(this::makeCall, 800);
            } else {
                makeCall();
                Toast.makeText(this, "Calcard: Aguardando atendimento...", Toast.LENGTH_SHORT).show();
            }
        });

        btnSpeaker.setOnClickListener(v -> {
            isSpeakerOn = !isSpeakerOn;
            if (CariocaInCallService.instance != null) {
                CariocaInCallService.instance.setSpeaker(isSpeakerOn);
            }
            updateSpeakerUI();
        });

        btnMainAction.setOnClickListener(v -> {
            if (CariocaInCallService.currentCall != null) {
                String dtmf = editDtmfSequence.getText().toString();
                if (!dtmf.isEmpty() && !isTyping) {
                    startDtmfTyping();
                } else if (isTyping) {
                    stopDtmfTyping();
                } else {
                    // Se clicar em Ligar com chamada ativa e sem DTMF, ele "reinicia" a chamada
                    CariocaInCallService.currentCall.disconnect();
                    Toast.makeText(this, "Reiniciando ligação...", Toast.LENGTH_SHORT).show();
                    handler.postDelayed(this::makeCall, 800);
                }
            } else {
                makeCall();
            }
        });

        btnHangupDedicated.setOnClickListener(v -> {
            if (CariocaInCallService.currentCall != null) {
                CariocaInCallService.currentCall.disconnect();
            }
        });

        btnClearDtmf.setOnClickListener(v -> {
            editDtmfSequence.setText("");
            Toast.makeText(this, "Campo limpo", Toast.LENGTH_SHORT).show();
        });

        seekDelay.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                currentDelay = progress + 100;
                tvDelayLabel.setText("Delay: " + currentDelay + "ms");
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
    }

    private void makeCall() {
        String number = editPhoneNumber.getText().toString().trim();
        if (number.isEmpty()) {
            Toast.makeText(this, "Digite um número!", Toast.LENGTH_SHORT).show();
            return;
        }
        
        // Salvar último número
        prefs.edit().putString(KEY_LAST_NUMBER, number).apply();
        
        Intent intent = new Intent(Intent.ACTION_CALL);
        intent.setData(Uri.parse("tel:" + number));
        startActivity(intent);
    }

    private void startDtmfTyping() {
        String sequence = editDtmfSequence.getText().toString().replaceAll("[^0-9*#]", "");
        if (sequence.isEmpty()) return;

        isTyping = true;
        layoutDtmfProgress.setVisibility(View.VISIBLE);
        progressDtmf.setMax(sequence.length());
        updateMainButtonUI();

        final char[] chars = sequence.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            final int index = i;
            final char c = chars[i];
            handler.postDelayed(() -> {
                if (!isTyping) return;
                CariocaInCallService.sendDtmf(c);
                tvTypingStatus.setText("Digitando: " + c + " (" + (index + 1) + "/" + chars.length + ")");
                progressDtmf.setProgress(index + 1);
                if (index == chars.length - 1) stopDtmfTyping();
            }, (long) i * currentDelay);
        }
    }

    private void stopDtmfTyping() {
        isTyping = false;
        handler.post(() -> {
            layoutDtmfProgress.setVisibility(View.GONE);
            updateMainButtonUI();
        });
    }

    private void updateSpeakerUI() {
        if (isSpeakerOn) {
            btnSpeaker.setText("🔊");
            btnSpeaker.setBackgroundTintList(ColorStateList.valueOf(0xFF10B981));
        } else {
            btnSpeaker.setText("🔈");
            btnSpeaker.setBackgroundTintList(ColorStateList.valueOf(0xFF4B5563));
        }
    }

    private void updateMainButtonUI() {
        if (CariocaInCallService.currentCall != null) {
            if (isTyping) {
                btnMainAction.setText("⏹ PARAR");
                btnMainAction.setBackgroundTintList(ColorStateList.valueOf(0xFFEF4444));
            } else if (!editDtmfSequence.getText().toString().isEmpty()) {
                btnMainAction.setText("⌨ DIGITAR DTMF");
                btnMainAction.setBackgroundTintList(ColorStateList.valueOf(0xFF7C3AED));
            } else {
                // Se estiver em ligação e sem DTMF, o botão principal pode ser redundante ou oculto
                btnMainAction.setText("CHAMADA ATIVA");
                btnMainAction.setBackgroundTintList(ColorStateList.valueOf(0xFF4B5563));
            }
            btnHangupDedicated.setVisibility(View.VISIBLE);
        } else {
            btnMainAction.setText("📞 LIGAR");
            btnMainAction.setBackgroundTintList(ColorStateList.valueOf(0xFF10B981));
            btnHangupDedicated.setVisibility(View.GONE);
        }
    }

    private void startCallStateUpdater() {
        handler.post(new Runnable() {
            @Override
            public void run() {
                updateCallStatus();
                handler.postDelayed(this, 1000);
            }
        });
    }

    private void updateCallStatus() {
        Call call = CariocaInCallService.currentCall;
        if (call != null) {
            layoutCallInfo.setVisibility(View.VISIBLE);
            String number = call.getDetails().getHandle().getSchemeSpecificPart();
            tvIncallNumber.setText(number);
            
            int state = call.getState();
            String stateText = "EM CHAMADA";
            
            if (state == Call.STATE_ACTIVE) {
                stateText = "CONECTADO (URA)";
                // Se a automação Calcard estiver pendente e a chamada acabou de ficar ativa
                if (isCalcardAutomationPending) {
                    isCalcardAutomationPending = false; // Resetar flag
                    // Esperar 4 segundos (entre 3 e 5) após o atendimento para digitar o 2
                    handler.postDelayed(() -> {
                        if (CariocaInCallService.currentCall != null && 
                            CariocaInCallService.currentCall.getState() == Call.STATE_ACTIVE) {
                            CariocaInCallService.sendDtmf('2');
                            Toast.makeText(MainActivity.this, "Calcard: Digitando 2 automaticamente...", Toast.LENGTH_SHORT).show();
                        }
                    }, 4000);
                }
            } else if (state == Call.STATE_DIALING) {
                stateText = "DISCANDO...";
            } else if (state == Call.STATE_RINGING) {
                stateText = "CHAMANDO...";
            }
            
            tvCallState.setText(stateText);

            if (CariocaInCallService.instance != null && isSpeakerOn) {
                CariocaInCallService.instance.setSpeaker(true);
            }
        } else {
            layoutCallInfo.setVisibility(View.GONE);
            isCalcardAutomationPending = false; // Resetar se a chamada cair
        }
        updateMainButtonUI();
    }

    private void checkIfDefaultDialer() {
        TelecomManager telecomManager = (TelecomManager) getSystemService(TELECOM_SERVICE);
        if (telecomManager != null) {
            String defaultDialer = telecomManager.getDefaultDialerPackage();
            if (defaultDialer != null && defaultDialer.equals(getPackageName())) {
                tvModuleStatus.setText("✓ DISCADOR PADRÃO");
                tvModuleStatus.setTextColor(0xFF10B981);
                btnSetDefault.setVisibility(View.GONE);
                return;
            }
        }
        tvModuleStatus.setText("✗ NÃO É PADRÃO");
        tvModuleStatus.setTextColor(0xFFEF4444);
        btnSetDefault.setVisibility(View.VISIBLE);
    }

    private void requestDefaultDialerRole() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            RoleManager roleManager = (RoleManager) getSystemService(ROLE_SERVICE);
            Intent intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER);
            startActivityForResult(intent, REQUEST_DEFAULT_DIALER);
        } else {
            Intent intent = new Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER);
            intent.putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, getPackageName());
            startActivityForResult(intent, REQUEST_DEFAULT_DIALER);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_DEFAULT_DIALER) checkIfDefaultDialer();
    }
}
