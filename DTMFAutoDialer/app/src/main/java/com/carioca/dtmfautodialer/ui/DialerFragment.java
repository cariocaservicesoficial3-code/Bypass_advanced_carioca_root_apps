package com.carioca.dtmfautodialer.ui;

import android.app.role.RoleManager;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.telecom.TelecomManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.carioca.dtmfautodialer.R;
import com.carioca.dtmfautodialer.service.FloatingWidgetService;

/**
 * DialerFragment v1.8
 * Aba 1: Discador
 * - Definir como discador padrão
 * - Fazer ligação
 * - Preparar sequência DTMF antes de ligar
 * - Iniciar/parar widget flutuante
 * - Configurar delay
 */
public class DialerFragment extends Fragment {

    public static final String TAG = "DialerFragment";

    // Interface para sincronizar delay com InCallFragment
    public interface OnDelayChangedListener {
        void onDelayChanged(int delayMs);
    }

    private OnDelayChangedListener delayListener;
    private int currentDelay = 500;

    private EditText editPhoneNumber;
    private EditText editDtmfPre;
    private TextView tvDelayLabel;

    public static DialerFragment newInstance() {
        return new DialerFragment();
    }

    public void setOnDelayChangedListener(OnDelayChangedListener listener) {
        this.delayListener = listener;
    }

    public void setDelay(int delayMs) {
        this.currentDelay = delayMs;
    }

    public int getDelay() {
        return currentDelay;
    }

    /**
     * Retorna o texto da sequência DTMF pré-configurada
     */
    public String getPreDtmfSequence() {
        if (editDtmfPre != null) {
            return editDtmfPre.getText().toString();
        }
        return "";
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_dialer, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        editPhoneNumber = view.findViewById(R.id.edit_phone_number);
        editDtmfPre = view.findViewById(R.id.edit_dtmf_pre);
        tvDelayLabel = view.findViewById(R.id.tv_delay_label);

        // Botão: Definir como Discador Padrão
        view.findViewById(R.id.btn_set_default_dialer).setOnClickListener(v -> requestDefaultDialerRole());

        // Botão: Colar número de telefone
        view.findViewById(R.id.btn_paste_number).setOnClickListener(v -> {
            ClipboardManager cb = (ClipboardManager) requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            if (cb != null && cb.hasPrimaryClip() && cb.getPrimaryClip() != null) {
                CharSequence text = cb.getPrimaryClip().getItemAt(0).getText();
                if (text != null) editPhoneNumber.setText(text);
            }
        });

        // Botão: Ligar
        view.findViewById(R.id.btn_call).setOnClickListener(v -> makeCall());

        // Botão: Colar sequência DTMF pré-configurada
        view.findViewById(R.id.btn_paste_dtmf_pre).setOnClickListener(v -> {
            ClipboardManager cb = (ClipboardManager) requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            if (cb != null && cb.hasPrimaryClip() && cb.getPrimaryClip() != null) {
                CharSequence text = cb.getPrimaryClip().getItemAt(0).getText();
                if (text != null) editDtmfPre.setText(text);
            }
        });

        // SeekBar de delay
        SeekBar seekDelay = view.findViewById(R.id.seek_delay);
        seekDelay.setProgress(currentDelay - 100); // offset: min 100ms
        updateDelayLabel(currentDelay);
        seekDelay.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                currentDelay = Math.max(100, progress + 100);
                updateDelayLabel(currentDelay);
                if (delayListener != null) {
                    delayListener.onDelayChanged(currentDelay);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // Botão: Iniciar Widget Flutuante
        view.findViewById(R.id.btn_start_widget).setOnClickListener(v -> startFloatingWidget());

        // Botão: Parar Widget Flutuante
        view.findViewById(R.id.btn_stop_widget).setOnClickListener(v -> {
            requireContext().stopService(new Intent(requireContext(), FloatingWidgetService.class));
            Toast.makeText(requireContext(), "Widget flutuante encerrado", Toast.LENGTH_SHORT).show();
        });
    }

    private void updateDelayLabel(int ms) {
        if (tvDelayLabel != null) {
            tvDelayLabel.setText(ms + "ms");
        }
    }

    private void makeCall() {
        String number = editPhoneNumber.getText().toString().trim();
        if (number.isEmpty()) {
            Toast.makeText(requireContext(), "Digite um número para ligar", Toast.LENGTH_SHORT).show();
            return;
        }
        // Limpar formatação para URI tel:
        String cleanNumber = number.replaceAll("[^0-9+*#]", "");
        Intent callIntent = new Intent(Intent.ACTION_CALL);
        callIntent.setData(Uri.parse("tel:" + cleanNumber));
        try {
            startActivity(callIntent);
        } catch (SecurityException e) {
            Toast.makeText(requireContext(), "Permissão de ligação negada. Verifique as permissões do app.", Toast.LENGTH_LONG).show();
        }
    }

    private void startFloatingWidget() {
        // Verificar permissão de overlay
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(requireContext())) {
            Toast.makeText(requireContext(),
                    "Permissão 'Exibir sobre outros apps' necessária. Redirecionando...",
                    Toast.LENGTH_LONG).show();
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + requireContext().getPackageName()));
            startActivity(intent);
            return;
        }
        Intent service = new Intent(requireContext(), FloatingWidgetService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            requireContext().startForegroundService(service);
        } else {
            requireContext().startService(service);
        }
        Toast.makeText(requireContext(), "Widget flutuante iniciado!", Toast.LENGTH_SHORT).show();
    }

    private void requestDefaultDialerRole() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            RoleManager roleManager = (RoleManager) requireContext().getSystemService(android.content.Context.ROLE_SERVICE);
            if (roleManager != null) {
                Intent intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER);
                startActivityForResult(intent, MainActivity.REQUEST_DEFAULT_DIALER);
            }
        } else {
            Intent intent = new Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER);
            intent.putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME,
                    requireContext().getPackageName());
            startActivityForResult(intent, MainActivity.REQUEST_DEFAULT_DIALER);
        }
    }
}
