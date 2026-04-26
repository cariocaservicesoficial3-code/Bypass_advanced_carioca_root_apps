package com.carioca.dtmfautodialer.ui;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;

/**
 * MainPagerAdapter v1.8
 * Gerencia as duas abas do ViewPager2:
 * - Posição 0: DialerFragment (Discador)
 * - Posição 1: InCallFragment (Em Ligação)
 */
public class MainPagerAdapter extends FragmentStateAdapter {

    private final DialerFragment dialerFragment;
    private final InCallFragment inCallFragment;

    public MainPagerAdapter(@NonNull FragmentActivity fragmentActivity) {
        super(fragmentActivity);
        dialerFragment = DialerFragment.newInstance();
        inCallFragment = InCallFragment.newInstance();

        // Sincronizar delay: quando o usuário muda na aba Discador, atualiza a aba Em Ligação
        dialerFragment.setOnDelayChangedListener(delayMs -> inCallFragment.setDelay(delayMs));
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
        if (position == 0) return dialerFragment;
        return inCallFragment;
    }

    @Override
    public int getItemCount() {
        return 2;
    }

    public DialerFragment getDialerFragment() {
        return dialerFragment;
    }

    public InCallFragment getInCallFragment() {
        return inCallFragment;
    }
}
