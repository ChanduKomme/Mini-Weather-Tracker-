// ForecastGraphActivity.java
package com.example.miniweathertracker;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import com.google.android.material.bottomnavigation.BottomNavigationView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Weekly report screen (graph). */
public class ForecastGraphActivity extends AppCompatActivity {

    private static final int REQ_LOC = 46;

    private TextView tvTitle, tvSubtitle, tvLocation;
    private ForecastGraphView graph;

    private Double lastLat = null, lastLon = null;
    private String lastPlace = null;

    /** If this screen was opened with explicit lat/lon (from a list row), ignore default broadcasts. */
    private boolean launchedWithExplicitPlace = false;

    /** Broadcast: default city set/unset. Mirrors CurrentWeatherActivity behavior. */
    private final BroadcastReceiver defaultChangedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!HomeLocationsActivity.ACTION_DEFAULT_CHANGED.equals(intent.getAction())) return;
            if (launchedWithExplicitPlace) return;

            boolean hasDefault = intent.getBooleanExtra("hasDefault", false);

            if (hasDefault) {
                double lat = intent.getDoubleExtra("lat", Double.NaN);
                double lon = intent.getDoubleExtra("lon", Double.NaN);
                String pretty = intent.getStringExtra("pretty");
                if (!Double.isNaN(lat) && !Double.isNaN(lon)) {
                    lastLat = lat;
                    lastLon = lon;
                    lastPlace = pretty;
                    tvLocation.setText(getString(R.string.cw_location,
                            lastPlace == null ? getString(R.string.cw_fetching) : lastPlace));
                    fetchWeekly(lastLat, lastLon, lastPlace);
                    if (lastPlace == null) resolvePlaceNameAsync(lat, lon);
                }
            } else {
                lastLat = lastLon = null;
                lastPlace = null;
                tvLocation.setText(getString(R.string.cw_location, getString(R.string.cw_fetching)));
                requestLocationAndFetch();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_forecast_graph);

        setupBottomBar(R.id.tab_graph);

        tvTitle = findViewById(R.id.tvFGTitle);
        tvSubtitle = findViewById(R.id.tvFGSubtitle);
        tvLocation = findViewById(R.id.tvFGLocation);
        graph = findViewById(R.id.forecastGraph);

        launchedWithExplicitPlace = getIntent() != null
                && getIntent().hasExtra("lat") && getIntent().hasExtra("lon");

        if (launchedWithExplicitPlace) {
            lastLat = getIntent().getDoubleExtra("lat", 0);
            lastLon = getIntent().getDoubleExtra("lon", 0);
            lastPlace = getIntent().getStringExtra("place");
            tvLocation.setText(getString(R.string.cw_location,
                    lastPlace == null ? getString(R.string.cw_location_unknown) : lastPlace));
            fetchWeekly(lastLat, lastLon, lastPlace);
            return;
        }

        PlaceStore ps = new PlaceStore(this);
        double[] def = ps.getDefaultLatLon();
        if (def != null) {
            lastLat = def[0];
            lastLon = def[1];
            tvLocation.setText(getString(R.string.cw_location, getString(R.string.cw_fetching)));
            fetchWeekly(lastLat, lastLon, null);
            resolvePlaceNameAsync(lastLat, lastLon);
        } else {
            tvLocation.setText(getString(R.string.cw_location, getString(R.string.cw_fetching)));
            requestLocationAndFetch();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        setupBottomBar(R.id.tab_graph);

        registerReceiver(defaultChangedReceiver,
                new IntentFilter(HomeLocationsActivity.ACTION_DEFAULT_CHANGED));

        if (!launchedWithExplicitPlace) {
            PlaceStore ps = new PlaceStore(this);
            double[] def = ps.getDefaultLatLon();
            if (def != null) {
                if (lastLat == null || Math.abs(lastLat - def[0]) > 1e-6 || Math.abs(lastLon - def[1]) > 1e-6) {
                    lastLat = def[0];
                    lastLon = def[1];
                    lastPlace = null;
                    tvLocation.setText(getString(R.string.cw_location, getString(R.string.cw_fetching)));
                    fetchWeekly(lastLat, lastLon, null);
                    resolvePlaceNameAsync(lastLat, lastLon);
                }
            } else {
                lastLat = lastLon = null;
                lastPlace = null;
                tvLocation.setText(getString(R.string.cw_location, getString(R.string.cw_fetching)));
                requestLocationAndFetch();
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            unregisterReceiver(defaultChangedReceiver);
        } catch (Exception ignore) {
        }
    }

    /* ----------------------- Bottom bar (unchanged flow) ----------------------- */
    private void setupBottomBar(int currentTabId) {
        BottomNavigationView bar = findViewById(R.id.bottomBar);
        if (bar == null) return;

        bar.setOnItemReselectedListener(item -> {
        });
        bar.setOnItemSelectedListener(null);
        bar.setSelectedItemId(currentTabId);

        bar.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == currentTabId) return true;

            Class<?> dest = null;
            if (id == R.id.tab_home) dest = HomeLocationsActivity.class;
            else if (id == R.id.tab_current) dest = CurrentWeatherActivity.class;
            else if (id == R.id.tab_graph) dest = ForecastGraphActivity.class;
            else if (id == R.id.tab_hourly) dest = HourlyForecastActivity.class;
            else if (id == R.id.tab_compass) dest = WindCompassActivity.class;

            if (dest != null) {
                Intent it = new Intent(this, dest);
                it.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                startActivity(it);
            }
            return true;
        });
    }
    /* -------------------------------------------------------------------------- */

    private void requestLocationAndFetch() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.ACCESS_COARSE_LOCATION},
                    REQ_LOC
            );
            return;
        }
        LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (lm == null) {
            showNoData();
            return;
        }

        Location best = null;
        for (String p : new String[]{LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER}) {
            try {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            } catch (SecurityException ignore) {
            }
        }
        if (best != null) {
            onGotLocation(best);
            return;
        }

        try {
            lm.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, this::onGotLocation, null);
        } catch (Exception e) {
            showNoData();
        }
    }

    private void onGotLocation(Location loc) {
        lastLat = loc.getLatitude();
        lastLon = loc.getLongitude();
        resolvePlaceNameAsync(lastLat, lastLon);
        fetchWeekly(lastLat, lastLon, lastPlace);
    }

    private void resolvePlaceNameAsync(double lat, double lon) {
        new Thread(() -> {
            String place = getPlaceName(lat, lon);
            runOnUiThread(() -> {
                lastPlace = place;
                tvLocation.setText(getString(R.string.cw_location,
                        place == null ? getString(R.string.cw_location_unknown) : place));
            });
        }).start();
    }

    private String getPlaceName(double lat, double lon) {
        try {
            Geocoder g = new Geocoder(this, Locale.getDefault());
            List<Address> list = g.getFromLocation(lat, lon, 1);
            if (list != null && !list.isEmpty()) {
                Address a = list.get(0);
                if (a.getLocality() != null) return a.getLocality();
                if (a.getSubAdminArea() != null) return a.getSubAdminArea();
                if (a.getAdminArea() != null) return a.getAdminArea();
                if (a.getCountryName() != null) return a.getCountryName();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void fetchWeekly(Double lat, Double lon, String place) {
        if (lat == null || lon == null) {
            showNoData();
            return;
        }

        tvLocation.setText(getString(R.string.cw_location,
                place == null ? getString(R.string.cw_fetching) : place));

        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                String url = "https://api.open-meteo.com/v1/forecast"
                        + "?latitude=" + lat
                        + "&longitude=" + lon
                        + "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_mean,weathercode"
                        + "&timezone=auto";

                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.setRequestMethod("GET");

                int code = conn.getResponseCode();
                if (code != HttpURLConnection.HTTP_OK) throw new RuntimeException("HTTP " + code);

                StringBuilder sb = new StringBuilder();
                try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                }

                JSONObject root = new JSONObject(sb.toString());
                JSONObject daily = root.getJSONObject("daily");

                JSONArray times = daily.getJSONArray("time");
                JSONArray tMaxs = daily.getJSONArray("temperature_2m_max");
                JSONArray tMins = daily.getJSONArray("temperature_2m_min");
                JSONArray precs = daily.optJSONArray("precipitation_probability_mean");
                JSONArray wcodes = daily.optJSONArray("weathercode");

                int n = Math.min(times.length(), 7);
                ArrayList<ForecastCard> list = new ArrayList<>(n);

                DateTimeFormatter f = DateTimeFormatter.ofPattern("yyyy-MM-dd");
                DateTimeFormatter dowFmt = DateTimeFormatter.ofPattern("EEE", Locale.getDefault());

                for (int i = 0; i < n; i++) {
                    LocalDate d = LocalDate.parse(times.getString(i), f);
                    String dow = d.format(dowFmt); // Mon, Tue, Wed, ...
                    int dayNum = d.getDayOfMonth();
                    float tMax = (float) tMaxs.getDouble(i);
                    float tMin = (float) tMins.getDouble(i);
                    int precip = (precs != null && !precs.isNull(i)) ? (int) Math.round(precs.getDouble(i)) : -1;
                    int wcode = (wcodes != null && !wcodes.isNull(i)) ? wcodes.getInt(i) : 0;

                    list.add(new ForecastCard(dow, dayNum, tMax, tMin, precip, wcode));
                }

                ForecastCard[] arr = list.toArray(new ForecastCard[0]);
                runOnUiThread(() -> {
                    graph.setCards(arr);
                    Toast.makeText(this, getString(R.string.cw_refreshed), Toast.LENGTH_SHORT).show();
                });

            } catch (Exception e) {
                runOnUiThread(() -> {
                    showNoData();
                    Toast.makeText(this, getString(R.string.cw_error), Toast.LENGTH_SHORT).show();
                });
            } finally {
                if (conn != null) conn.disconnect();
            }
        }).start();
    }

    private void showNoData() {
        if (graph != null) graph.setCards(new ForecastCard[0]);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_LOC) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                requestLocationAndFetch();
            } else {
                showNoData();
            }
        }
    }

    @Override
    public void onBackPressed() {
        // From any bottom-tab page, Back should exit to the phone’s Home.
        moveTaskToBack(true);
    }
}
