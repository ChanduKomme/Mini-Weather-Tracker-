package com.example.miniweathertracker;

import android.content.Intent;
import android.os.Bundle;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.bottomnavigation.BottomNavigationView;

public class MenuActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_menu);

        // (unchanged) Top buttons
        findViewById(R.id.btnCurrent)
                .setOnClickListener(v ->
                        startActivity(new Intent(this, CurrentWeatherActivity.class)));
        findViewById(R.id.btnForecast)
                .setOnClickListener(v ->
                        startActivity(new Intent(this, ForecastGraphActivity.class)));
        findViewById(R.id.btnHourly)
                .setOnClickListener(v ->
                        startActivity(new Intent(this, HourlyForecastActivity.class)));
        findViewById(R.id.btnCompass)
                .setOnClickListener(v ->
                        startActivity(new Intent(this, WindCompassActivity.class)));
        findViewById(R.id.btnHome)
                .setOnClickListener(v ->
                        startActivity(new Intent(this, HomeLocationsActivity.class)));

        // 🔹 NEW: bottom navigation – highlight & reuse activities
        BottomNavigationView bar = findViewById(R.id.bottomBar);
        if (bar != null) {
            bar.setSelectedItemId(R.id.tab_home); // 🔹 NEW: show Home tab selected on this screen

            bar.setOnItemSelectedListener(item -> {
                Intent it = null;
                int id = item.getItemId();
                if (id == R.id.tab_home) {
                    it = new Intent(this, HomeLocationsActivity.class);
                } else if (id == R.id.tab_current) {
                    it = new Intent(this, CurrentWeatherActivity.class);
                } else if (id == R.id.tab_graph) {
                    it = new Intent(this, ForecastGraphActivity.class);
                } else if (id == R.id.tab_hourly) {
                    it = new Intent(this, HourlyForecastActivity.class);
                } else if (id == R.id.tab_compass) {
                    it = new Intent(this, WindCompassActivity.class);
                }
                if (it != null) {
                    // 🔹 NEW: bring existing activity to front instead of creating duplicates
                    it.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                    startActivity(it);
                    return true;
                }
                return false;
            });
        }
    }
}
