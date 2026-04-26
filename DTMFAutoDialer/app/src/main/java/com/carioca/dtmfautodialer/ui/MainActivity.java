package com.carioca.dtmfautodialer.ui;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.carioca.dtmfautodialer.R;
import com.carioca.dtmfautodialer.service.FloatingWidgetService;

/**
 * MainActivity - Interface principal do módulo DTMF Auto Dialer
 *
 * Permite ao usuário:
 * 1. Verificar se o módulo está ativo no LSPosed
 * 2. Configurar preferências (delay, auto-start, etc.)
 * 3. Testar a funcionalidade com números de exemplo
 * 4. Iniciar o widget flutuante manualmente
 * 5. Ver instruções de uso
 */
public class MainActivity extends AppCompatActivity {

    private static final int OVERLAY_PERMISSION_REQUEST = 1001;
    private static final String PREFS_NAME = "dtmf_prefs";

    private EditText editDigits;
    private TextView tvModuleStatus;
    private TextView tvDelay;
    private SeekBar seekDelay;
    private Button btnStartWidget;
    private Button btnPaste;
    private Button btnClearHistory;
    private Switch switchAutoStart;
    private TextView tvHistory;

    private SharedPreferences prefs;
    private int currentDelay = 300;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        currentDelay = prefs.getInt("delay_ms", 300);

        initViews();
        checkModuleStatus();
        loadHistory();
    }

    private void initViews() {
        tvModuleStatus = findViewById(R.id.tv_module_status);
        editDigits = findViewById(R.id.edit_digits);
        tvDelay = findViewById(R.id.tv_delay);
        seekDelay = findViewById(R.id.seek_delay);
        btnStartWidget = findViewById(R.id.btn_start_widget);
        btnPaste = findViewById(R.id.btn_paste);
        btnClearHistory = findViewById(R.id.btn_clear_history);
        switchAutoStart = findViewById(R.id.switch_auto_start);
        tvHistory = findViewById(R.id.tv_history);

        // Configurar delay
        seekDelay.setMax(900);
        seekDelay.setProgress(currentDelay - 100);
        tvDelay.setText(currentDelay + "ms");

        seekDelay.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                currentDelay = progress + 100;
                tvDelay.setText(currentDelay + "ms");
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                prefs.edit().putInt("delay_ms", currentDelay).apply();
            }
        });

        // Botão iniciar widget
        btnStartWidget.setOnClickListener(v -> startFloatingWidget());

        // Botão colar
        btnPaste.setOnClickListener(v -> pasteFromClipboard());

        // Botão limpar histórico
        btnClearHistory.setOnClickListener(v -> {
            prefs.edit().remove("history").apply();
            tvHistory.setText("Nenhum histórico");
        });

        // Switch auto-start
        switchAutoStart.setChecked(prefs.getBoolean("auto_start", true));
        switchAutoStart.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean("auto_start", isChecked).apply();
        });
    }

    /**
     * Verifica se o módulo está ativo no LSPosed
     */
    private void checkModuleStatus() {
        // O módulo está ativo se esta flag foi setada pelo hook
        boolean isActive = isModuleActive();

        if (isActive) {
            tvModuleStatus.setText("MÓDULO ATIVO");
            tvModuleStatus.setTextColor(getColor(android.R.color.holo_green_light));
        } else {
            tvModuleStatus.setText("MÓDULO INATIVO - Ative no LSPosed");
            tvModuleStatus.setTextColor(getColor(android.R.color.holo_red_light));
        }
    }

    /**
     * Verifica se o módulo Xposed está ativo
     * Este método será hookado pelo framework para retornar true
     */
    private boolean isModuleActive() {
        // Se o módulo estiver ativo no LSPosed, este método será interceptado
        // e retornará true. Caso contrário, retorna false.
        return false;
    }

    /**
     * Inicia o widget flutuante
     */
    private void startFloatingWidget() {
        // Verificar permissão de overlay
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            new AlertDialog.Builder(this)
                    .setTitle("Permissão Necessária")
                    .setMessage("O DTMF Auto Dialer precisa de permissão para exibir sobre outros apps.\n\n" +
                            "Isso é necessário para mostrar o widget flutuante durante as ligações.")
                    .setPositiveButton("Conceder", (dialog, which) -> {
                        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + getPackageName()));
                        startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST);
                    })
                    .setNegativeButton("Cancelar", null)
                    .show();
            return;
        }

        // Iniciar o serviço do widget flutuante
        String digits = editDigits.getText().toString().trim();
        Intent serviceIntent = new Intent(this, FloatingWidgetService.class);
        if (!digits.isEmpty()) {
            serviceIntent.putExtra("digits", digits);
            saveToHistory(digits);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }

        Toast.makeText(this, "Widget flutuante iniciado!", Toast.LENGTH_SHORT).show();

        // Minimizar o app
        moveTaskToBack(true);
    }

    /**
     * Cola da área de transferência
     */
    private void pasteFromClipboard() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null && clipboard.hasPrimaryClip()) {
            ClipData clip = clipboard.getPrimaryClip();
            if (clip != null && clip.getItemCount() > 0) {
                CharSequence text = clip.getItemAt(0).getText();
                if (text != null) {
                    editDigits.setText(text);
                    editDigits.setSelection(editDigits.getText().length());
                    Toast.makeText(this, "Colado!", Toast.LENGTH_SHORT).show();
                }
            }
        }
    }

    /**
     * Salva no histórico
     */
    private void saveToHistory(String digits) {
        String history = prefs.getString("history", "");
        String cleanDigits = digits.replaceAll("[^0-9*#.-]", "");
        String timestamp = new java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.getDefault())
                .format(new java.util.Date());
        String entry = timestamp + " - " + cleanDigits;

        if (!history.isEmpty()) {
            history = entry + "\n" + history;
        } else {
            history = entry;
        }

        // Manter apenas as últimas 20 entradas
        String[] lines = history.split("\n");
        if (lines.length > 20) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 20; i++) {
                if (i > 0) sb.append("\n");
                sb.append(lines[i]);
            }
            history = sb.toString();
        }

        prefs.edit().putString("history", history).apply();
        loadHistory();
    }

    /**
     * Carrega o histórico
     */
    private void loadHistory() {
        String history = prefs.getString("history", "");
        if (history.isEmpty()) {
            tvHistory.setText("Nenhum histórico");
        } else {
            tvHistory.setText(history);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == OVERLAY_PERMISSION_REQUEST) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
                startFloatingWidget();
            } else {
                Toast.makeText(this, "Permissão de overlay não concedida", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkModuleStatus();
    }
}
