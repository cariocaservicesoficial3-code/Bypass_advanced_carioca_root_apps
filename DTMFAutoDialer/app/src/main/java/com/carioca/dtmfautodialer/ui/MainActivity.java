package com.carioca.dtmfautodialer.ui;

import android.app.role.RoleManager;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.telecom.Call;
import android.telecom.CallAudioState;
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
 * MainActivity v3.0 - TELA ÚNICA
 * Reformulação total baseada no feedback do vídeo.
 */
public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_DEFAULT_DIALER = 123;
    public static final String EXTRA_GO_TO_INCALL = "go_to_incall";

    private TextView tvModuleStatus, tvIncallNumber, tvCallState, tvTypingStatus, tvDelayLabel;
    private Button btnSetDefault, btnCalcard, btnSpeaker, btnMainAction;
    private EditText editPhoneNumber, editDtmfSequence;
    private LinearLayout layoutCallInfo, layoutDtmfProgress;
    private ProgressBar progressDtmf;
    private SeekBar seekDelay;
    private GridLayout gridKeypad;

    private boolean isSpeakerOn = true; // Viva-voz padrão ON
    private boolean isTyping = false;
    private int currentDelay = 500;
    private Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        setupKeypad();
        setupListeners();
        checkIfDefaultDialer();
        
        // Auto-viva-voz ao iniciar se houver chamada
        if (CariocaInCallService.instance != null) {
            CariocaInCallService.instance.setSpeaker(true);
        }
        
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
        
        editPhoneNumber = findViewById(R.id.edit_phone_number);
        editDtmfSequence = findViewById(R.id.edit_dtmf_sequence);
        
        layoutCallInfo = findViewById(R.id.layout_call_info);
        layoutDtmfProgress = findViewById(R.id.layout_dtmf_progress);
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
                    
                    // Se estiver em ligação, envia o DTMF na hora
                    if (CariocaInCallService.currentCall != null) {
                        CariocaInCallService.sendDtmf(key.charAt(0));
                    }
                });
            }
        }
    }

    private void setupListeners() {
        btnSetDefault.setOnClickListener(v -> requestDefaultDialerRole());
        
        btnCalcard.setOnClickListener(v -> {
            editPhoneNumber.setText("08006484455");
            makeCall();
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
                // Se está em ligação e tem sequência, digita. Senão, desliga.
                String dtmf = editDtmfSequence.getText().toString();
                if (!dtmf.isEmpty() && !isTyping) {
                    startDtmfTyping();
                } else if (isTyping) {
                    stopDtmfTyping();
                } else {
                    CariocaInCallService.currentCall.disconnect();
                }
            } else {
                makeCall();
            }
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

        // Auto-colar ao clicar no campo de sequência
        editDtmfSequence.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                ClipboardManager cb = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (cb != null && cb.hasPrimaryClip()) {
                    CharSequence text = cb.getPrimaryClip().getItemAt(0).getText();
                    if (text != null && editDtmfSequence.getText().toString().isEmpty()) {
                        editDtmfSequence.setText(text);
                    }
                }
            }
        });
    }

    private void makeCall() {
        String number = editPhoneNumber.getText().toString().trim();
        if (number.isEmpty()) {
            Toast.makeText(this, "Digite um número!", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_CALL);
        intent.setData(Uri.parse("tel:" + number));
        startActivity(intent);
    }

    private void startDtmfTyping() {
        String sequence = editDtmfSequence.getText().toString().replaceAll("[^0-9*#]", "");
        if (sequence.isEmpty()) return;

        isTyping = true;
        btnMainAction.setText("⏹ PARAR");
        layoutDtmfProgress.setVisibility(View.VISIBLE);
        progressDtmf.setMax(sequence.length());

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
            btnSpeaker.setText("🔊 VIVA-VOZ: ON");
            btnSpeaker.setBackgroundTintList(ColorStateList.valueOf(0xFF10B981));
        } else {
            btnSpeaker.setText("🔈 VIVA-VOZ: OFF");
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
                btnMainAction.setText("📵 DESLIGAR");
                btnMainAction.setBackgroundTintList(ColorStateList.valueOf(0xFFEF4444));
            }
        } else {
            btnMainAction.setText("📞 LIGAR");
            btnMainAction.setBackgroundTintList(ColorStateList.valueOf(0xFF10B981));
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
            tvCallState.setText("CHAMADA ATIVA");
            
            // Auto-viva-voz se acabou de começar
            if (CariocaInCallService.instance != null && isSpeakerOn) {
                CariocaInCallService.instance.setSpeaker(true);
            }
        } else {
            layoutCallInfo.setVisibility(View.GONE);
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
