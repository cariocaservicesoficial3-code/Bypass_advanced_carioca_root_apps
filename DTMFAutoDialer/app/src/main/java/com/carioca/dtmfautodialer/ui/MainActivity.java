package com.carioca.dtmfautodialer.ui;

import android.content.Intent;
import android.os.Bundle;
import android.telecom.TelecomManager;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.viewpager2.widget.ViewPager2;

import com.carioca.dtmfautodialer.R;
import com.carioca.dtmfautodialer.service.CariocaInCallService;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

/**
 * MainActivity v1.8 — Carioca Dialer
 *
 * Estrutura de abas:
 *   Aba 0 — "📞 DISCADOR"   : DialerFragment
 *   Aba 1 — "⌨ EM LIGAÇÃO" : InCallFragment
 *
 * Comportamento:
 * - Ao iniciar, verifica se é discador padrão e exibe status no header
 * - Ao navegar para a aba "Em Ligação", notifica o fragment (auto-cola clipboard)
 * - Quando o InCallService detecta uma chamada e abre esta Activity,
 *   navega automaticamente para a aba "Em Ligação"
 */
public class MainActivity extends AppCompatActivity {

    public static final int REQUEST_DEFAULT_DIALER = 123;
    public static final String EXTRA_GO_TO_INCALL = "go_to_incall";

    private TabLayout tabLayout;
    private ViewPager2 viewPager;
    private MainPagerAdapter pagerAdapter;
    private TextView tvModuleStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvModuleStatus = findViewById(R.id.tv_module_status);
        tabLayout = findViewById(R.id.tab_layout);
        viewPager = findViewById(R.id.view_pager);

        // Configurar adapter com as duas abas
        pagerAdapter = new MainPagerAdapter(this);
        viewPager.setAdapter(pagerAdapter);

        // Conectar TabLayout ao ViewPager2
        new TabLayoutMediator(tabLayout, viewPager, (tab, position) -> {
            if (position == 0) {
                tab.setText("📞  DISCADOR");
            } else {
                tab.setText("⌨  EM LIGAÇÃO");
            }
        }).attach();

        // Listener para notificar fragments quando a aba muda
        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                if (tab.getPosition() == 1) {
                    // Usuário entrou na aba "Em Ligação"
                    // Passar sequência pré-configurada da aba Discador
                    String preDtmf = pagerAdapter.getDialerFragment().getPreDtmfSequence();
                    if (!preDtmf.isEmpty()) {
                        pagerAdapter.getInCallFragment().setDtmfSequence(preDtmf);
                    }
                    // Notificar fragment (auto-cola clipboard se campo vazio)
                    pagerAdapter.getInCallFragment().onTabSelected();
                }
            }
            @Override public void onTabUnselected(TabLayout.Tab tab) {}
            @Override public void onTabReselected(TabLayout.Tab tab) {}
        });

        // Verificar se deve ir direto para aba Em Ligação
        handleIntent(getIntent());

        // Atualizar status de discador padrão
        checkIfDefaultDialer();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkIfDefaultDialer();
        // Se há chamada ativa e estamos na aba Discador, sugerir ir para Em Ligação
        if (CariocaInCallService.currentCall != null && viewPager.getCurrentItem() == 0) {
            // Navegar automaticamente para a aba Em Ligação
            viewPager.setCurrentItem(1, true);
        }
    }

    private void handleIntent(Intent intent) {
        if (intent != null && intent.getBooleanExtra(EXTRA_GO_TO_INCALL, false)) {
            // Navegar para aba Em Ligação
            viewPager.post(() -> viewPager.setCurrentItem(1, true));
        }
    }

    private void checkIfDefaultDialer() {
        TelecomManager telecomManager = (TelecomManager) getSystemService(TELECOM_SERVICE);
        if (telecomManager != null && telecomManager.getDefaultDialerPackage().equals(getPackageName())) {
            tvModuleStatus.setText("✓ DISCADOR PADRÃO");
            tvModuleStatus.setTextColor(0xFF10B981); // verde
        } else {
            tvModuleStatus.setText("✗ NÃO É PADRÃO");
            tvModuleStatus.setTextColor(0xFFEF4444); // vermelho
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_DEFAULT_DIALER) {
            checkIfDefaultDialer();
        }
    }
}
