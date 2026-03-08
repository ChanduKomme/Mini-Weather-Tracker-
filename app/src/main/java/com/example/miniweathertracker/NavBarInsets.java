package com.example.miniweathertracker.ui;

import android.view.ViewGroup;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import com.google.android.material.bottomnavigation.BottomNavigationView;

/** Keeps the bottom bar content above the system gesture area without changing its visual height. */
public final class NavBarInsets {
    private NavBarInsets() {}

    public static void apply(BottomNavigationView bar) {
        if (bar == null) return;

        // Make sure nothing inside gets clipped when we add bottom padding.
        if (bar instanceof ViewGroup) {
            ((ViewGroup) bar).setClipToPadding(false);
        }

        ViewCompat.setOnApplyWindowInsetsListener(bar, (v, insets) -> {
            int sysBottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom;

            // Do NOT change the bar’s measured height; just add invisible bottom padding so
            // icons+labels are visually centered and never overlap the gesture handle.
            v.setPadding(
                    v.getPaddingLeft(),
                    v.getPaddingTop(),          // top stays 0
                    v.getPaddingRight(),
                    sysBottom                   // push content up above gesture bar
            );
            return insets;
        });
        ViewCompat.requestApplyInsets(bar);
    }
}
