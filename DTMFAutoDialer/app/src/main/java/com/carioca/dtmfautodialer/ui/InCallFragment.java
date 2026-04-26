package com.carioca.dtmfautodialer.ui;

import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.telecom.Call;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.carioca.dtmfautodialer.R;
import com.carioca.dtmfautodialer.service.CariocaInCallService;

/**
 * InCallFragment v1.8
 * Aba 2: Em Ligação
 * - Mostra número e estado da chamada em tempo real
 * - Auto-cola sequência DTMF ao entrar na aba
 * - Envia tons DTMF diretamente via API Android (playDtmfTone)
 * - Delay configurável
 * - Botão para parar digitação e desligar
 */
public class InCallFragment extends Fragment {

    public static final String TAG = "InCallFragment";

    private TextView tvIncallNumber;
    private TextView tvCallState;
    private EditText editDtmfIncall;
    private TextView tvTypingStatus;
    private ProgressBar progressDtmf;
    private Button btnStartDtmf;
    private Button btnStopDtmf;
    private Button btnHangup;
    private TextView tvDelayIncallLabel;
    private SeekBar seekDelayIncall;

    private Handler handler = new Handler(Looper.getMainLooper());
    private boolean isTyping = false;
    private int currentDelay = 500;

    // Runnable para atualizar estado da chamada periodicamente
    private Runnable callStateUpdater;

    public static InCallFragment newInstance() {
        return new InCallFragment();
    }

    public void setDelay(int delayMs) {
        this.currentDelay = delayMs;
        if (seekDelayIncall != null) {
            seekDelayIncall.setProgress(Math.max(0, delayMs - 100));
        }
        if (tvDelayIncallLabel != null) {
            tvDelayIncallLabel.setText(delayMs + "ms");
        }
    }

    /**
     * Preenche o campo de sequência DTMF com texto externo (ex: da aba Discador)
     */
    public void setDtmfSequence(String sequence) {
        if (editDtmfIncall != null && sequence != null && !sequence.isEmpty()) {
            editDtmfIncall.setText(sequence);
        }
    }

    /**
     * Chamado quando o usuário navega para esta aba.
     * Tenta auto-colar da área de transferência se o campo estiver vazio.
     */
    public void onTabSelected() {
        updateCallInfo();
        if (editDtmfIncall != null && editDtmfIncall.getText().toString().isEmpty()) {
            autoPasteFromClipboard();
        }
    }

    private void autoPasteFromClipboard() {
        if (getContext() == null) return;
        ClipboardManager cb = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (cb != null && cb.hasPrimaryClip() && cb.getPrimaryClip() != null) {
            CharSequence text = cb.getPrimaryClip().getItemAt(0).getText();
            if (text != null && !text.toString().isEmpty()) {
                editDtmfIncall.setText(text);
                Toast.makeText(requireContext(), "Sequência colada automaticamente!", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_in_call, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        tvIncallNumber = view.findViewById(R.id.tv_incall_number);
        tvCallState = view.findViewById(R.id.tv_call_state);
        editDtmfIncall = view.findViewById(R.id.edit_dtmf_incall);
        tvTypingStatus = view.findViewById(R.id.tv_typing_status);
        progressDtmf = view.findViewById(R.id.progress_dtmf);
        btnStartDtmf = view.findViewById(R.id.btn_start_dtmf);
        btnStopDtmf = view.findViewById(R.id.btn_stop_dtmf);
        btnHangup = view.findViewById(R.id.btn_hangup);
        tvDelayIncallLabel = view.findViewById(R.id.tv_delay_incall_label);
        seekDelayIncall = view.findViewById(R.id.seek_delay_incall);

        // Inicializar delay
        seekDelayIncall.setProgress(Math.max(0, currentDelay - 100));
        tvDelayIncallLabel.setText(currentDelay + "ms");
        seekDelayIncall.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                currentDelay = Math.max(100, progress + 100);
                tvDelayIncallLabel.setText(currentDelay + "ms");
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // Botão: Colar
        view.findViewById(R.id.btn_paste_incall).setOnClickListener(v -> {
            ClipboardManager cb = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
            if (cb != null && cb.hasPrimaryClip() && cb.getPrimaryClip() != null) {
                CharSequence text = cb.getPrimaryClip().getItemAt(0).getText();
                if (text != null) editDtmfIncall.setText(text);
            }
        });

        // Botão: Limpar
        view.findViewById(R.id.btn_clear_incall).setOnClickListener(v -> {
            editDtmfIncall.setText("");
            tvTypingStatus.setText("Pronto para digitar");
            progressDtmf.setVisibility(View.GONE);
        });

        // Botão: Digitar Agora
        btnStartDtmf.setOnClickListener(v -> startAutoDial());

        // Botão: Parar Digitação
        btnStopDtmf.setOnClickListener(v -> stopTyping());

        // Botão: Desligar
        btnHangup.setOnClickListener(v -> {
            if (CariocaInCallService.currentCall != null) {
                CariocaInCallService.currentCall.disconnect();
            } else {
                Toast.makeText(requireContext(), "Nenhuma chamada ativa", Toast.LENGTH_SHORT).show();
            }
        });

        // Iniciar atualizador de estado da chamada
        startCallStateUpdater();
        updateCallInfo();
    }

    private void startCallStateUpdater() {
        callStateUpdater = new Runnable() {
            @Override
            public void run() {
                if (isAdded()) {
                    updateCallInfo();
                    handler.postDelayed(this, 1000); // Atualiza a cada 1 segundo
                }
            }
        };
        handler.post(callStateUpdater);
    }

    private void updateCallInfo() {
        if (tvIncallNumber == null || tvCallState == null) return;

        Call call = CariocaInCallService.currentCall;
        if (call != null) {
            // Número
            Call.Details details = call.getDetails();
            if (details != null && details.getHandle() != null) {
                String uri = details.getHandle().toString();
                tvIncallNumber.setText(uri.replace("tel:", ""));
            } else {
                tvIncallNumber.setText("Número oculto");
            }

            // Estado
            int state = call.getState();
            String stateText;
            switch (state) {
                case Call.STATE_ACTIVE:
                    stateText = "Em andamento";
                    tvCallState.setTextColor(0xFF10B981); // success green
                    break;
                case Call.STATE_DIALING:
                    stateText = "Discando...";
                    tvCallState.setTextColor(0xFFFBBF24); // warning yellow
                    break;
                case Call.STATE_RINGING:
                    stateText = "Chamando...";
                    tvCallState.setTextColor(0xFFFBBF24);
                    break;
                case Call.STATE_HOLDING:
                    stateText = "Em espera";
                    tvCallState.setTextColor(0xFF9CA3AF);
                    break;
                case Call.STATE_DISCONNECTED:
                    stateText = "Desconectado";
                    tvCallState.setTextColor(0xFFEF4444); // danger red
                    break;
                default:
                    stateText = "Conectando...";
                    tvCallState.setTextColor(0xFF9CA3AF);
            }
            tvCallState.setText(stateText);
        } else {
            tvIncallNumber.setText("Aguardando ligação...");
            tvCallState.setText("Nenhuma chamada ativa");
            tvCallState.setTextColor(0xFF9CA3AF);
        }
    }

    private void startAutoDial() {
        String raw = editDtmfIncall.getText().toString();
        // Aceita 0-9, *, # — remove pontuação e espaços
        final String digits = raw.replaceAll("[^0-9*#]", "");

        if (digits.isEmpty()) {
            Toast.makeText(requireContext(), "Nenhum dígito para discar! Cole uma sequência primeiro.", Toast.LENGTH_SHORT).show();
            return;
        }

        if (CariocaInCallService.currentCall == null) {
            Toast.makeText(requireContext(), "Nenhuma chamada ativa! Faça uma ligação primeiro.", Toast.LENGTH_LONG).show();
            return;
        }

        // Verificar se a chamada está ativa (não apenas discando)
        int state = CariocaInCallService.currentCall.getState();
        if (state != Call.STATE_ACTIVE) {
            Toast.makeText(requireContext(), "Aguarde a chamada ser atendida antes de digitar.", Toast.LENGTH_SHORT).show();
            return;
        }

        isTyping = true;
        btnStartDtmf.setEnabled(false);
        btnStopDtmf.setVisibility(View.VISIBLE);
        progressDtmf.setVisibility(View.VISIBLE);
        progressDtmf.setMax(digits.length());
        progressDtmf.setProgress(0);
        tvTypingStatus.setText("Iniciando digitação...");
        tvTypingStatus.setTextColor(0xFFFBBF24); // amarelo

        final char[] chars = digits.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            final int index = i;
            final char c = chars[i];

            handler.postDelayed(() -> {
                if (!isTyping) return;

                // Enviar DTMF via API oficial do Android
                CariocaInCallService.sendDtmf(c);

                if (isAdded()) {
                    tvTypingStatus.setText("Digitando: " + c + "  (" + (index + 1) + " / " + chars.length + ")");
                    progressDtmf.setProgress(index + 1);

                    if (index == chars.length - 1) {
                        finishTyping();
                    }
                }
            }, (long) i * currentDelay);
        }
    }

    private void stopTyping() {
        isTyping = false;
        handler.removeCallbacksAndMessages(null);
        // Re-registrar o atualizador de estado
        startCallStateUpdater();
        finishTypingUI("Digitação interrompida.");
    }

    private void finishTyping() {
        isTyping = false;
        handler.post(() -> {
            if (isAdded()) {
                finishTypingUI("Concluído! Todos os dígitos enviados.");
                tvTypingStatus.setTextColor(0xFF10B981); // verde
            }
        });
    }

    private void finishTypingUI(String message) {
        if (tvTypingStatus != null) tvTypingStatus.setText(message);
        if (btnStartDtmf != null) btnStartDtmf.setEnabled(true);
        if (btnStopDtmf != null) btnStopDtmf.setVisibility(View.GONE);
        if (progressDtmf != null) progressDtmf.setVisibility(View.GONE);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        isTyping = false;
        handler.removeCallbacksAndMessages(null);
    }
}
