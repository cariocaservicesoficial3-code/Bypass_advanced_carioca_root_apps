package com.carioca.dtmfautodialer.ui;

import android.app.role.RoleManager;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.telecom.TelecomManager;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.carioca.dtmfautodialer.R;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_ID_SET_DEFAULT_DIALER = 123;
    private TextView tvStatus;
    private EditText editDigits;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus = findViewById(R.id.tv_module_status);
        editDigits = findViewById(R.id.edit_digits);

        Button btnStart = findViewById(R.id.btn_start_widget);
        btnStart.setText("DEFINIR COMO DISCADOR PADRÃO");
        btnStart.setOnClickListener(v -> requestDefaultDialerRole());

        findViewById(R.id.btn_paste).setOnClickListener(v -> {
            ClipboardManager cb = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cb.hasPrimaryClip()) {
                editDigits.setText(cb.getPrimaryClip().getItemAt(0).getText());
            }
        });

        checkIfDefaultDialer();
    }

    private void checkIfDefaultDialer() {
        TelecomManager telecomManager = (TelecomManager) getSystemService(TELECOM_SERVICE);
        if (telecomManager.getDefaultDialerPackage().equals(getPackageName())) {
            tvStatus.setText("DISCADOR PADRÃO ATIVO");
            tvStatus.setTextColor(getResources().getColor(android.R.color.holo_green_light));
        } else {
            tvStatus.setText("DISCADOR PADRÃO INATIVO");
            tvStatus.setTextColor(getResources().getColor(android.R.color.holo_red_light));
        }
    }

    private void requestDefaultDialerRole() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            RoleManager roleManager = (RoleManager) getSystemService(ROLE_SERVICE);
            Intent intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER);
            startActivityForResult(intent, REQUEST_ID_SET_DEFAULT_DIALER);
        } else {
            Intent intent = new Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER);
            intent.putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, getPackageName());
            startActivityForResult(intent, REQUEST_ID_SET_DEFAULT_DIALER);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_ID_SET_DEFAULT_DIALER) {
            checkIfDefaultDialer();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkIfDefaultDialer();
    }
}
