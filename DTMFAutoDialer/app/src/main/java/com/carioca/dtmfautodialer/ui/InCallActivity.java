package com.carioca.dtmfautodialer.ui;

import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.telecom.Call;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.carioca.dtmfautodialer.R;
import com.carioca.dtmfautodialer.service.CariocaInCallService;

public class InCallActivity extends AppCompatActivity {

    private TextView tvPhoneNumber;
    private EditText editSequence;
    private TextView tvTypingStatus;
    private ProgressBar progressBar;
    private Button btnStart;
    private Handler handler = new Handler(Looper.getMainLooper());
    private boolean isTyping = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_in_call);

        tvPhoneNumber = findViewById(R.id.tv_phone_number);
        editSequence = findViewById(R.id.edit_dtmf_sequence);
        tvTypingStatus = findViewById(R.id.tv_typing_status);
        progressBar = findViewById(R.id.progress_typing);
        btnStart = findViewById(R.id.btn_start_dtmf);

        updateCallInfo();

        findViewById(R.id.btn_paste_incall).setOnClickListener(v -> {
            ClipboardManager cb = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cb.hasPrimaryClip()) {
                editSequence.setText(cb.getPrimaryClip().getItemAt(0).getText());
            }
        });

        findViewById(R.id.btn_clear_incall).setOnClickListener(v -> editSequence.setText(""));

        btnStart.setOnClickListener(v -> startAutoDial());

        findViewById(R.id.btn_hangup).setOnClickListener(v -> {
            if (CariocaInCallService.currentCall != null) {
                CariocaInCallService.currentCall.disconnect();
            }
            finish();
        });
    }

    private void updateCallInfo() {
        if (CariocaInCallService.currentCall != null) {
            Call.Details details = CariocaInCallService.currentCall.getDetails();
            if (details != null && details.getHandle() != null) {
                String uri = details.getHandle().toString();
                tvPhoneNumber.setText(uri.replace("tel:", ""));
            }
        }
    }

    private void startAutoDial() {
        String digits = editSequence.getText().toString().replaceAll("[^0-9*#]", "");
        if (digits.isEmpty()) {
            Toast.makeText(this, "Nenhum dígito para discar!", Toast.LENGTH_SHORT).show();
            return;
        }

        if (CariocaInCallService.currentCall == null) {
            Toast.makeText(this, "Nenhuma chamada ativa!", Toast.LENGTH_SHORT).show();
            return;
        }

        isTyping = true;
        btnStart.setEnabled(false);
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setMax(digits.length());
        
        final char[] chars = digits.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            final int index = i;
            final char c = chars[i];
            
            handler.postDelayed(() -> {
                if (!isTyping) return;
                
                // ENVIAR DTMF DIRETAMENTE PELA API DO ANDROID
                CariocaInCallService.sendDtmf(c);
                
                tvTypingStatus.setText("Digitando: " + c + " (" + (index + 1) + "/" + chars.length + ")");
                progressBar.setProgress(index + 1);

                if (index == chars.length - 1) {
                    isTyping = false;
                    btnStart.setEnabled(true);
                    progressBar.setVisibility(View.GONE);
                    tvTypingStatus.setText("Concluído!");
                }
            }, (long) i * 500); // Delay de 500ms para estabilidade
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (CariocaInCallService.currentCall == null) {
            finish();
        }
    }
}
