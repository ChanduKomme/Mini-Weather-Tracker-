package com.example.miniweathertracker;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.miniweathertracker.ui.NavBarInsets;
import com.google.android.material.bottomnavigation.BottomNavigationView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

public class HourlyForecastActivity extends AppCompatActivity {

    private static final int REQ_LOC_HOURLY = 66;

    private static final String WEATHER_PREFS = "weather_prefs";
    private static final String KEY_LAST_LOCATION_CITY = "last_location_city";

    private static final String PLACES_PREFS = "places_store";
    private static final String KEY_DEF_LAT = "default_lat";
    private static final String KEY_DEF_LON = "default_lon";
    private static final String KEY_DEF_PRETTY = "default_pretty";

    private TextView tvLocation;
    private LinearLayout listContainer;

    private SharedPreferences weatherPrefs;
    private SharedPreferences placesPrefs;
    private SharedPreferences.OnSharedPreferenceChangeListener placesListener;

    private PlaceStore placeStore;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_hourly_forecast);

        // Views
        findViewById(R.id.tvHTitle);
        findViewById(R.id.tvHSubtitle);
        tvLocation = findViewById(R.id.tvHLocation);
        listContainer = findViewById(R.id.listContainer);

        // Bottom bar setup + insets so icons are fully visible (no clipping)
        setupBottomBar(R.id.tab_hourly);
        BottomNavigationView bottomBar = findViewById(R.id.bottomBar);
        NavBarInsets.apply(bottomBar); // invisible hit-slop = system bottom inset

        // Also pad the Hourly list so the last card never sits under the gesture bar
        View scroll = findViewById(R.id.scroll);
        if (scroll != null) {
            ViewCompat.setOnApplyWindowInsetsListener(scroll, (v, insets) -> {
                int sysBottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom;
                int barHeight = getResources().getDimensionPixelSize(R.dimen.bnv_icon); // visual bar height
                v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(),
                        sysBottom + barHeight);
                return insets;
            });
            ViewCompat.requestApplyInsets(scroll);
        }

        // Stores
        weatherPrefs = getSharedPreferences(WEATHER_PREFS, MODE_PRIVATE);
        placeStore = new PlaceStore(this);
        placesPrefs = getSharedPreferences(PLACES_PREFS, MODE_PRIVATE);

        placesListener = (sp, key) -> {
            if (KEY_DEF_LAT.equals(key) || KEY_DEF_LON.equals(key) || KEY_DEF_PRETTY.equals(key)) {
                applySourcePolicy();
            }
        };

        // If lat/lon passed in, show that immediately
        if (getIntent() != null && getIntent().hasExtra("lat") && getIntent().hasExtra("lon")) {
            double lat = getIntent().getDoubleExtra("lat", 0);
            double lon = getIntent().getDoubleExtra("lon", 0);
            String place = getIntent().getStringExtra("place");
            tvLocation.setText(getString(R.string.cw_location,
                    place == null ? getString(R.string.cw_location_unknown) : place));
            fetchHourly(lat, lon);
            return;
        }

        applySourcePolicy();
    }

    @Override protected void onStart() {
        super.onStart();
        if (placesPrefs != null && placesListener != null) {
            placesPrefs.registerOnSharedPreferenceChangeListener(placesListener);
        }
    }

    @Override protected void onStop() {
        super.onStop();
        if (placesPrefs != null && placesListener != null) {
            placesPrefs.unregisterOnSharedPreferenceChangeListener(placesListener);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        setupBottomBar(R.id.tab_hourly);
        // re-apply insets in case window insets changed
        BottomNavigationView bottomBar = findViewById(R.id.bottomBar);
        NavBarInsets.apply(bottomBar);
        applySourcePolicy();
    }

    private void setupBottomBar(int currentTabId) {
        BottomNavigationView bar = findViewById(R.id.bottomBar);
        if (bar == null) return;

        bar.setOnItemReselectedListener(item -> {});
        bar.setOnItemSelectedListener(null);
        bar.setSelectedItemId(currentTabId);

        bar.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == currentTabId) return true;

            Class<?> dest = null;
            if (id == R.id.tab_home)        dest = HomeLocationsActivity.class;
            else if (id == R.id.tab_current) dest = CurrentWeatherActivity.class;
            else if (id == R.id.tab_graph)   dest = ForecastGraphActivity.class;
            else if (id == R.id.tab_hourly)  dest = HourlyForecastActivity.class;
            else if (id == R.id.tab_compass) dest = WindCompassActivity.class;

            if (dest != null) {
                Intent it = new Intent(this, dest);
                it.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                startActivity(it);
            }
            return true;
        });
    }

    private void applySourcePolicy() {
        if (placeStore.hasDefault()) {
            double[] latlon = placeStore.getDefaultLatLon();
            if (latlon != null) {
                String label = safeTrim(placeStore.getDefaultPrettyOrNull());
                if (isEmpty(label)) label = derivePrettyFromSaved(latlon[0], latlon[1]);

                if (!isEmpty(label)) {
                    tvLocation.setText(getString(R.string.cw_location, label));
                } else {
                    reverseGeocodeAndSet(latlon[0], latlon[1]);
                }

                fetchHourly(latlon[0], latlon[1]);
                return;
            }
        }

        String cached = weatherPrefs.getString(KEY_LAST_LOCATION_CITY, "");
        tvLocation.setText(getString(R.string.cw_location, cached));
        requestLocationAndFetch();
    }

    private String derivePrettyFromSaved(double lat, double lon) {
        List<PlaceStore.Place> all = placeStore.getAll();
        for (PlaceStore.Place p : all) {
            if (same(p.lat, lat) && same(p.lon, lon)) {
                if (!isEmpty(p.pretty)) return p.pretty.trim();
                if (!isEmpty(p.name)) return p.name.trim();
            }
        }
        return null;
    }

    private void reverseGeocodeAndSet(double lat, double lon) {
        new Thread(() -> {
            String place = getPlaceName(lat, lon);
            if (place == null) return;
            runOnUiThread(() ->
                    tvLocation.setText(getString(R.string.cw_location, place)));
        }).start();
    }

    private void requestLocationAndFetch() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOC_HOURLY);
            return;
        }
        LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (lm == null) { useMock(); return; }

        Location best = null;
        for (String p : new String[]{LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER}) {
            try {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            } catch (SecurityException ignore) {}
        }
        if (best != null) { onGotLocation(best); return; }

        try {
            lm.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, this::onGotLocation, null);
        } catch (Exception e) {
            useMock();
        }
    }

    private void onGotLocation(Location loc) {
        double lat = loc.getLatitude();
        double lon = loc.getLongitude();

        new Thread(() -> {
            String place = getPlaceName(lat, lon);
            weatherPrefs.edit()
                    .putString(KEY_LAST_LOCATION_CITY,
                            place == null ? getString(R.string.cw_location_unknown) : place)
                    .apply();
            runOnUiThread(() -> tvLocation.setText(
                    getString(R.string.cw_location, place == null ? getString(R.string.cw_location_unknown) : place)));
        }).start();

        fetchHourly(lat, lon);
    }

    /** Single helper used everywhere. */
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
        } catch (Exception ignored) {}
        return null;
    }

    private String tempUnitParam() {
        String c = Locale.getDefault().getCountry();
        if ("US".equals(c) || "BS".equals(c) || "BZ".equals(c) || "KY".equals(c)
                || "LR".equals(c) || "PW".equals(c) || "FM".equals(c) || "MH".equals(c)) {
            return "fahrenheit";
        }
        return "celsius";
    }

    /* ===================== Accurate HOURLY (anchored, same-index binding) ===================== */
    private void fetchHourly(double lat, double lon) {
        new Thread(() -> {
            try {
                String unit = tempUnitParam();

                // Anchor to API "now" and fetch hourly arrays we bind to (same indices for all fields).
                JSONObject root = getJsonObject(lat, lon, unit);
                JSONObject hourly = root.getJSONObject("hourly");

                JSONArray times   = hourly.getJSONArray("time");           // epoch seconds
                JSONArray tArr    = hourly.getJSONArray("temperature_2m");
                JSONArray fArr    = hourly.optJSONArray("apparent_temperature");
                JSONArray pArr    = hourly.optJSONArray("precipitation_probability");
                JSONArray codeArr = hourly.getJSONArray("weathercode");
                JSONArray isDay   = hourly.optJSONArray("is_day");

                // Use API's current_weather.time as the anchor to pick the first displayed hour.
                long anchorMs = 0L;
                JSONObject cw = root.optJSONObject("current_weather");
                if (cw != null) anchorMs = cw.optLong("time", 0L) * 1000L;

                int start = (anchorMs > 0) ? findIndexAtOrAfter(times, anchorMs) : findStartIndexEpoch(times);

                int count = Math.min(24, Math.max(0, times.length() - start));
                if (count == 0) count = Math.min(24, times.length());
                // Safety: ensure start is in range
                start = Math.max(0, Math.min(start, Math.max(0, times.length() - 1)));

                // Labels MUST follow the forecast city's timezone (never the device)
                TimeZone tz = resolveApiTimezone(root);
                SimpleDateFormat outFmt = new SimpleDateFormat("h a", Locale.getDefault());
                outFmt.setTimeZone(tz);

                HourlyItem[] items = new HourlyItem[count];
                for (int i = 0; i < count; i++) {
                    int idx = start + i;

                    long epochSec = times.getLong(idx);
                    String label = outFmt.format(new Date(epochSec * 1000L));

                    // EXACT same index for all values
                    double tExact   = tArr.getDouble(idx);
                    double feelsEx  = (fArr != null && !fArr.isNull(idx)) ? fArr.getDouble(idx) : tExact;
                    int precipExact = (pArr != null && !pArr.isNull(idx)) ? pArr.getInt(idx) : 0;
                    int code        = codeArr.getInt(idx);
                    boolean night   = (isDay != null && !isDay.isNull(idx)) && isDay.getInt(idx) == 0;

                    // Keep UI as integers while faithfully reflecting the per-hour values
                    int temp  = (int) Math.round(tExact);
                    int feels = (int) Math.round(feelsEx);

                    items[i] = new HourlyItem(label, temp, feels, precipExact, code, night);
                }

                runOnUiThread(() -> bindList(items));
            } catch (Exception e) {
                runOnUiThread(() -> {
                    Toast.makeText(this, getString(R.string.hourly_error), Toast.LENGTH_SHORT).show();
                    useMock();
                });
            }
        }).start();
    }

    @NonNull
    private static JSONObject getJsonObject(double lat, double lon, String unit) throws IOException, JSONException {
        String url = "https://api.open-meteo.com/v1/forecast?latitude=" + lat +
                "&longitude=" + lon +
                "&hourly=temperature_2m,apparent_temperature,precipitation_probability,weathercode,is_day" +
                "&current_weather=true" +
                "&timezone=auto&timeformat=unixtime&past_days=1&forecast_days=2&temperature_unit=" + unit;

        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        conn.setRequestMethod("GET");
        if (conn.getResponseCode() != HttpURLConnection.HTTP_OK)
            throw new RuntimeException("HTTP " + conn.getResponseCode());

        BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        conn.disconnect();

        JSONObject root   = new JSONObject(sb.toString());
        return root;
    }

    /** Fallback: first hour >= device now (30m tolerance) */
    private int findStartIndexEpoch(JSONArray times) {
        try {
            long now = System.currentTimeMillis();
            long tolerance = 30L * 60L * 1000L;
            for (int i = 0; i < times.length(); i++) {
                long tMs = times.getLong(i) * 1000L;
                if (tMs + tolerance >= now) return i;
            }
        } catch (Exception ignored) {}
        return 0;
    }

    /** Preferred: first hour whose epoch >= anchorMs (API current_weather.time) */
    private int findIndexAtOrAfter(JSONArray times, long anchorMs) {
        try {
            for (int i = 0; i < times.length(); i++) {
                long tMs = times.getLong(i) * 1000L;
                if (tMs >= anchorMs) return i;
            }
        } catch (Exception ignored) {}
        return 0;
    }

    @SuppressLint("SetTextI18n")
    private void bindList(HourlyItem[] items) {
        LayoutInflater inf = LayoutInflater.from(this);
        listContainer.removeAllViews();
        for (HourlyItem it : items) {
            View row = inf.inflate(R.layout.item_hourly, listContainer, false);
            ((TextView) row.findViewById(R.id.tvTime)).setText(it.timeLabel);
            ((TextView) row.findViewById(R.id.tvTemp)).setText(it.temp + "°");
            ((TextView) row.findViewById(R.id.tvFeels))
                    .setText(getString(R.string.hourly_realfeel, it.feels));
            ((TextView) row.findViewById(R.id.tvPrecip)).setText(it.precip + "%");
            ((TextView) row.findViewById(R.id.tvIcon)).setText(iconForCode(it.wcode, it.isNight));
            listContainer.addView(row);
        }
    }

    /**
     * Strict Open-Meteo weathercode → emoji with day/night behavior:
     * Clear (day) ☀️ | Clear (night) 🌙
     * Partly cloudy (day) 🌤 | Partly cloudy (night) 🌙☁️
     * Cloudy ☁️
     * Rain 🌧 | Thunderstorm ⛈ | Snow ❄️/🌨 | Fog 🌫
     */
    private String iconForCode(int code, boolean isNight) {
        switch (code) {
            case 0:  return isNight ? "🌙" : "☀️";           // clear
            case 1:  return isNight ? "🌙" : "☀️";           // mainly clear
            case 2:  return isNight ? "🌙☁️" : "🌤";         // partly cloudy
            case 3:  return isNight ? "🌙☁️" : "☁️";         // overcast
            case 45:
            case 48: return "🌫";                            // fog
            case 51: case 53: case 55: case 56: case 57:
            case 61: case 63: case 65: case 66: case 67:
            case 80: case 81: case 82: return "🌧";          // drizzle/rain/showers
            case 71: case 73: case 75: case 77: return "❄️"; // snow
            case 85: case 86: return "🌨";                   // snow showers
            case 95: case 96: case 99: return "⛈";          // thunderstorms
            default: return isNight ? "🌙☁️" : "☁️";
        }
    }

    private void useMock() {
        tvLocation.setText(getString(R.string.cw_location, getString(R.string.cw_location_unknown)));
        HourlyItem[] items = new HourlyItem[8];
        String[] times = {"4 AM","5 AM","6 AM","7 AM","8 AM","9 AM","10 AM","11 AM"};
        int base = 18;
        for (int i = 0; i < 8; i++) {
            int t = base + i;
            items[i] = new HourlyItem(times[i], t, t - 1, (i * 7) % 60, (i < 2 ? 3 : (i < 4 ? 80 : 0)), i < 2);
        }
        bindList(items);
    }

    /* ===== timezone helper: always use forecast city's timezone ===== */
    private static TimeZone resolveApiTimezone(JSONObject root) {
        String tzId = root.optString("timezone", null);
        if (!tzId.trim().isEmpty()) {
            TimeZone tz = TimeZone.getTimeZone(tzId);
            if (!"GMT".equals(tz.getID()) || "GMT".equals(tzId)) {
                return tz;
            }
        }
        int offSec = root.optInt("utc_offset_seconds", 0);
        int hours = offSec / 3600;
        int mins  = Math.abs((offSec % 3600) / 60);
        String gmtId = String.format(Locale.US, "GMT%+d:%02d", hours, mins);
        return TimeZone.getTimeZone(gmtId);
    }

    /* utils */
    private static boolean isEmpty(String s) { return s == null || s.trim().isEmpty(); }
    private static String safeTrim(String s) { return s == null ? null : s.trim(); }
    private static boolean same(double a, double b) { return Math.abs(a - b) < 1e-6; }
}
