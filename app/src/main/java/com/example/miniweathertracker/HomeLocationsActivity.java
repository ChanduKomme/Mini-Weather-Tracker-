// HomeLocationsActivity.java
package com.example.miniweathertracker;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomnavigation.BottomNavigationView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class HomeLocationsActivity extends AppCompatActivity {

    public static final String ACTION_DEFAULT_CHANGED =
            "com.example.miniweathertracker.ACTION_DEFAULT_PLACE_CHANGED";

    private EditText etQuery;
    private RecyclerView rvSaved;
    private RecyclerView rvSuggest;

    // Saved + suggestions data
    private final List<PlaceStore.Place> saved = new ArrayList<>();
    private final List<PlaceStore.Place> suggestions = new ArrayList<>();

    // Adapters
    private HomeCardAdapter savedAdapter;
    private SuggestAdapter suggestAdapter;

    private PlaceStore store;

    // Debounced suggestions
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable suggestDebounce;

    // Weather cache (unchanged)
    private static class WeatherBrief {
        Integer tempRounded;
        int code = -1;
    }

    private final Map<String, WeatherBrief> weatherCache = new HashMap<>();
    private final Set<String> inFlight = new HashSet<>();

    private static String keyFor(double lat, double lon) {
        return String.format(Locale.US, "%.4f,%.4f", lat, lon);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_home_locations);

        // Keep keyboard hidden initially and keep bottom bar fixed (pan content only)
        getWindow().setSoftInputMode(
                android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
                        | android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        );

        setupBottomBar(R.id.tab_home);
        store = new PlaceStore(this);

        etQuery = findViewById(R.id.etQuery);
        rvSaved = findViewById(R.id.rvSaved);
        rvSuggest = findViewById(R.id.rvSuggest);

        // Ensure the EditText doesn't auto-focus on launch
        etQuery.clearFocus();
        View root = findViewById(R.id.rootHome);
        if (root != null) root.requestFocus();

        // Saved list — single full-width card per row
        rvSaved.setLayoutManager(new LinearLayoutManager(this));
        savedAdapter = new HomeCardAdapter(saved);
        rvSaved.setAdapter(savedAdapter);

        // Suggestions dropdown (vertical list)
        rvSuggest.setLayoutManager(new LinearLayoutManager(this));
        suggestAdapter = new SuggestAdapter(suggestions, new SuggestAdapter.OnAct() {
            @Override
            public void onPick(PlaceStore.Place p) {
                // On tap: add immediately
                store.add(p);
                refreshSaved();

                // --- UI visibility: restore cards after selection ---
                rvSuggest.setVisibility(View.GONE);
                rvSaved.setVisibility(View.VISIBLE);
                etQuery.setText("");

                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.hideSoftInputFromWindow(etQuery.getWindowToken(), 0);
                Toast.makeText(HomeLocationsActivity.this,
                        getString(R.string.home_saved_msg), Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onAdd(PlaceStore.Place p) {
                /* unused now */
            }

            @Override
            public void onRemove(PlaceStore.Place p) {
                boolean wasDefault = store.isDefault(p.lat, p.lon);
                store.remove(p.lat, p.lon);
                if (wasDefault) {
                    store.clearDefault();
                    sendDefaultChangedBroadcast(false, null, null, null);
                }
                refreshSaved();
                rvSuggest.setVisibility(View.GONE);
                rvSaved.setVisibility(View.VISIBLE);
                Toast.makeText(HomeLocationsActivity.this,
                        getString(R.string.home_removed_msg), Toast.LENGTH_SHORT).show();
            }
        });
        rvSuggest.setAdapter(suggestAdapter);

        // Autocomplete while typing (UI-only visibility tweak)
        etQuery.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {
            }

            @Override
            public void onTextChanged(CharSequence s, int st, int b, int c) {
                if (suggestDebounce != null) handler.removeCallbacks(suggestDebounce);
                final String q = s.toString().trim();

                if (q.isEmpty()) {
                    // Clear search -> restore cards, hide suggestions list
                    rvSuggest.setVisibility(View.GONE);
                    suggestions.clear();
                    suggestAdapter.notifyDataSetChanged();
                    rvSaved.setVisibility(View.VISIBLE);
                    return;
                }

                // While typing -> show only suggestions list, hide cards
                rvSaved.setVisibility(View.GONE);
                rvSuggest.setVisibility(View.VISIBLE);

                suggestDebounce = () -> fetchSuggestions(q);
                handler.postDelayed(suggestDebounce, 250);
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        refreshSaved();
    }

    @Override
    protected void onResume() {
        super.onResume();
        setupBottomBar(R.id.tab_home);
    }

    /** Bottom bar: show icons/labels normally (Home visible on Home). */
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

    private void refreshSaved() {
        saved.clear();
        saved.addAll(store.getAll());
        savedAdapter.notifyDataSetChanged();
        for (PlaceStore.Place p : saved) requestWeatherIfNeeded(p);
    }

    private void sendDefaultChangedBroadcast(boolean hasDefault, Double lat, Double lon, String pretty) {
        Intent i = new Intent(ACTION_DEFAULT_CHANGED);
        i.putExtra("hasDefault", hasDefault);
        if (hasDefault && lat != null && lon != null) {
            i.putExtra("lat", lat);
            i.putExtra("lon", lon);
            if (pretty != null) i.putExtra("pretty", pretty);
        }
        sendBroadcast(i);
    }

    private void fetchSuggestions(String q) {
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                String url = "https://geocoding-api.open-meteo.com/v1/search"
                        + "?name=" + URLEncoder.encode(q, StandardCharsets.UTF_8.name())
                        + "&count=8"
                        + "&language=" + URLEncoder.encode(Locale.getDefault().getLanguage(), StandardCharsets.UTF_8.name());
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                if (conn.getResponseCode() != 200) throw new RuntimeException("HTTP " + conn.getResponseCode());

                StringBuilder sb = new StringBuilder();
                try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                }

                JSONObject root = new JSONObject(sb.toString());
                JSONArray arr = root.optJSONArray("results");

                List<PlaceStore.Place> temp = new ArrayList<>();
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.getJSONObject(i);
                        PlaceStore.Place p = new PlaceStore.Place();
                        String name = o.optString("name", "");
                        String admin1 = o.optString("admin1", "");
                        String country = o.optString("country", "");
                        p.name = name;
                        p.pretty = TextUtils.isEmpty(admin1) ? (name + ", " + country)
                                : (name + ", " + admin1 + ", " + country);
                        p.lat = o.optDouble("latitude", Double.NaN);
                        p.lon = o.optDouble("longitude", Double.NaN);
                        if (!Double.isNaN(p.lat) && !Double.isNaN(p.lon)) temp.add(p);
                    }
                }

                runOnUiThread(() -> {
                    suggestions.clear();
                    suggestions.addAll(temp);
                    suggestAdapter.notifyDataSetChanged();
                    // Keep rvSaved hidden while query is non-empty; rvSuggest already visible from TextWatcher
                });

            } catch (Exception e) {
                runOnUiThread(() -> rvSuggest.setVisibility(View.GONE));
            } finally {
                if (conn != null) conn.disconnect();
            }
        }).start();
    }

    // ---------- Cards + single placeholder adapter ----------
    private class HomeCardAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private static final int TYPE_PLACE = 0;
        private static final int TYPE_EMPTY = 1;

        private final List<PlaceStore.Place> data;

        HomeCardAdapter(List<PlaceStore.Place> data) {
            this.data = data;
        }

        // Always exactly one placeholder (at the end)
        @Override
        public int getItemCount() {
            return data.size() + 1;
        }

        @Override
        public int getItemViewType(int position) {
            return (position < data.size()) ? TYPE_PLACE : TYPE_EMPTY;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inf = LayoutInflater.from(parent.getContext());
            if (viewType == TYPE_PLACE) {
                View v = inf.inflate(R.layout.item_place, parent, false);
                return new PlaceVH(v);
            } else {
                View v = inf.inflate(R.layout.item_empty_card, parent, false);
                return new EmptyVH(v);
            }
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder vh, int pos) {
            if (getItemViewType(pos) == TYPE_EMPTY) {
                ((EmptyVH) vh).bind();
            } else {
                PlaceStore.Place p = data.get(pos);
                ((PlaceVH) vh).bind(p);
            }
        }

        // --- place card ---
        class PlaceVH extends RecyclerView.ViewHolder {
            View row;
            TextView tvName, tvStar, tvTemp, tvCond, tvCoords;
            TextView tvMakeDefault, tvRemove;

            PlaceVH(View v) {
                super(v);
                row = v.findViewById(R.id.row_place);
                tvName = v.findViewById(R.id.tvPlaceName);
                tvStar = v.findViewById(R.id.tvDefaultStar);
                tvTemp = v.findViewById(R.id.tvPlaceTemp);
                tvCond = v.findViewById(R.id.tvPlaceCond);
                tvCoords = v.findViewById(R.id.tvPlaceCoords);
                tvMakeDefault = v.findViewById(R.id.tvMakeDefault);
                tvRemove = v.findViewById(R.id.tvRemove);
            }

            void bind(PlaceStore.Place p) {
                tvName.setText(p.pretty);
                tvCoords.setText(String.format(Locale.US, "%.4f, %.4f", p.lat, p.lon));

                boolean isDefault = store.isDefault(p.lat, p.lon);
                tvStar.setText(isDefault ? "★" : "");

                String k = keyFor(p.lat, p.lon);
                WeatherBrief wb = weatherCache.get(k);
                if (wb != null && wb.tempRounded != null) {
                    tvTemp.setText(String.format(Locale.getDefault(), "%d°", wb.tempRounded));
                } else {
                    tvTemp.setText("—°");
                    requestWeatherIfNeeded(p);
                }

                // REMOVE the emoji beside temperature: hide the condition view
                tvCond.setText("");
                tvCond.setVisibility(View.GONE);

                row.setOnClickListener(v -> {
                    Intent it = new Intent(HomeLocationsActivity.this, CurrentWeatherActivity.class);
                    it.putExtra("lat", p.lat);
                    it.putExtra("lon", p.lon);
                    it.putExtra("place", p.pretty);
                    startActivity(it);
                });

                tvMakeDefault.setOnClickListener(v -> {
                    if (store.isDefault(p.lat, p.lon)) {
                        store.clearDefault();
                        tvStar.setText("");
                        Toast.makeText(HomeLocationsActivity.this,
                                getString(R.string.home_unset_default_msg), Toast.LENGTH_SHORT).show();
                        notifyDataSetChanged();
                        sendDefaultChangedBroadcast(false, null, null, null);
                    } else {
                        store.setDefault(p.lat, p.lon);
                        tvStar.setText("⭐");
                        Toast.makeText(HomeLocationsActivity.this,
                                getString(R.string.home_set_default_msg), Toast.LENGTH_SHORT).show();
                        notifyDataSetChanged();
                        sendDefaultChangedBroadcast(true, p.lat, p.lon, p.pretty);
                    }
                });

                tvRemove.setOnClickListener(v -> {
                    boolean wasDefault = store.isDefault(p.lat, p.lon);
                    store.remove(p.lat, p.lon);
                    if (wasDefault) {
                        store.clearDefault();
                        sendDefaultChangedBroadcast(false, null, null, null);
                    }
                    refreshSaved();
                    Toast.makeText(HomeLocationsActivity.this,
                            getString(R.string.home_removed_msg), Toast.LENGTH_SHORT).show();
                });
            }
        }

        // --- placeholder row (single, always at end) ---
        class EmptyVH extends RecyclerView.ViewHolder {
            View btnAdd;

            EmptyVH(View v) {
                super(v);
                btnAdd = v.findViewById(R.id.btnAddEmpty);
            }

            void bind() {
                btnAdd.setOnClickListener(v -> {
                    etQuery.requestFocus();
                    InputMethodManager imm =
                            (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null) imm.showSoftInput(etQuery, InputMethodManager.SHOW_IMPLICIT);
                    rvSuggest.setVisibility(View.VISIBLE);
                    rvSaved.setVisibility(View.GONE); // while user is searching
                });
            }
        }
    }

    // ---------- Suggestions ----------
    private static class SuggestAdapter extends RecyclerView.Adapter<SuggestAdapter.SVH> {
        interface OnAct {
            void onPick(PlaceStore.Place p);

            void onAdd(PlaceStore.Place p);

            void onRemove(PlaceStore.Place p);
        }

        private final List<PlaceStore.Place> data;
        private final OnAct act;

        SuggestAdapter(List<PlaceStore.Place> data, OnAct act) {
            this.data = data;
            this.act = act;
        }

        @NonNull
        @Override
        public SVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_suggest_row, parent, false);
            return new SVH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull SVH h, int pos) {
            final PlaceStore.Place place = data.get(pos);
            h.tvName.setText(place.pretty);

            // Tap row -> add immediately
            h.itemView.setOnClickListener(v -> act.onPick(place));

            // Hide buttons inside dropdown (we add on tap now)
            h.btnAddRemove.setVisibility(View.GONE);
            h.btnAddRemove.setOnClickListener(null);
            h.btnDefault.setVisibility(View.GONE);
            h.btnDefault.setOnClickListener(null);
        }

        @Override
        public int getItemCount() {
            return data.size();
        }

        static class SVH extends RecyclerView.ViewHolder {
            TextView tvName;
            TextView btnDefault;
            TextView btnAddRemove;

            SVH(@NonNull View v) {
                super(v);
                tvName = v.findViewById(R.id.tvSuggestName);
                btnDefault = v.findViewById(R.id.btnSugDefault);
                btnAddRemove = v.findViewById(R.id.btnSugAddRemove);
            }
        }
    }

    /* =================== Weather helpers (unchanged) =================== */

    private void requestWeatherIfNeeded(PlaceStore.Place p) {
        final String k = keyFor(p.lat, p.lon);
        if (weatherCache.containsKey(k) || inFlight.contains(k)) return;

        inFlight.add(k);
        new Thread(() -> {
            try {
                String unit = tempUnitParam();
                String url = "https://api.open-meteo.com/v1/forecast?latitude=" + p.lat +
                        "&longitude=" + p.lon +
                        "&current=temperature_2m,weather_code" +
                        "&timezone=auto&temperature_unit=" + unit;

                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                if (c.getResponseCode() != 200) throw new RuntimeException("HTTP");

                BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();
                c.disconnect();

                JSONObject cur = new JSONObject(sb.toString()).optJSONObject("current");
                WeatherBrief wb = new WeatherBrief();
                if (cur != null) {
                    double t = cur.optDouble("temperature_2m", Double.NaN);
                    if (!Double.isNaN(t)) wb.tempRounded = (int) Math.round(t);
                    wb.code = cur.optInt("weather_code", -1);
                }
                synchronized (weatherCache) {
                    weatherCache.put(k, wb);
                }
                runOnUiThread(() -> savedAdapter.notifyDataSetChanged());
            } catch (Exception ignore) {
            } finally {
                inFlight.remove(k);
            }
        }).start();
    }

    private String tempUnitParam() {
        String c = Locale.getDefault().getCountry();
        if ("US".equals(c) || "BS".equals(c) || "BZ".equals(c) || "KY".equals(c)
                || "LR".equals(c) || "PW".equals(c) || "FM".equals(c) || "MH".equals(c)) {
            return "fahrenheit";
        }
        return "celsius";
    }

    private String emojiForCode(int code) {
        boolean rain = (code >= 51 && code <= 67) || (code >= 80 && code <= 82);
        boolean cloudy = (code == 1 || code == 2 || code == 3) || (code >= 45 && code <= 48);
        if (code == 0) return "☀️";
        if (rain) return "🌧️";
        if (cloudy) return "☁️";
        if (code >= 71 && code <= 77) return "❄️";
        if (code >= 95) return "⛈️";
        return "—";
    }

    @Override
    public void onBackPressed() {
        // From any bottom-tab page, Back should exit to the phone’s Home.
        super.onBackPressed();
        moveTaskToBack(true);
    }
}
