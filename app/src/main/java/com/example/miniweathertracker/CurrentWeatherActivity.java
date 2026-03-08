// CurrentWeatherActivity.java
package com.example.miniweathertracker;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.ImageView;
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
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

public class CurrentWeatherActivity extends AppCompatActivity implements SensorEventListener {

    private static final int REQ_LOC = 44;

    private SensorManager sm;
    private Sensor light;

    private TextView tvTemp, tvHumidity, tvCondition, tvMode, tvHint, tvLocation;
    private TextView tvMin, tvMax;
    private TextView tvFeelsLike, tvSunrise, tvSunset;

    // AQI / Wind (gusts-only)
    private TextView tvAqi, tvAqiCategory, tvGusts, tvWindArrow;

    // UV card
    private TextView tvUvValue, tvUvCategory;

    private final WeatherRepository repo = new WeatherRepository();
    private boolean isDay = true;

    private Double lastLat = null, lastLon = null;
    private String lastPlace = null;

    private boolean launchedWithExplicitPlace = false;

    // ---------- Ambient light control ----------
    private static final float LUX_ENTER_DARK = 50f;
    private static final float LUX_EXIT_DARK = 80f;
    private static final float LUX_NIGHT_ON = LUX_ENTER_DARK;
    private static final float LUX_DAY_ON = LUX_EXIT_DARK;
    private static final float LUX_MIN = 0.1f;
    private static final float LUX_MAX = 40000f;
    private static final float LUX_JUMP_MULT = 3.5f;
    private static final float SMOOTH_TAU_S = 1.0f;
    private static final long LIGHT_SAMPLE_MIN_MS = 500L;
    private static final long MODE_DWELL_DARK_MS = 0L;
    private static final long MODE_DWELL_LIGHT_MS = 900L;

    private float luxAvg = 0f;
    private long lastLightSampleMs = 0L;
    private Long dayTriggerAtMs = null, nightTriggerAtMs = null;

    // App-only brightness levels & helper
    private static final float DARK_BRIGHTNESS = 0.18f;
    private static final float BRIGHTNESS_DEFAULT = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;

    private void setAppBrightness(float value) {
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        if (lp.screenBrightness == value) return;
        lp.screenBrightness = value;
        getWindow().setAttributes(lp);
    }

    private void updateLocationNameAsync(double lat, double lon, String prettyIfAny) {
        String showing = (prettyIfAny == null || prettyIfAny.trim().isEmpty())
                ? getString(R.string.cw_fetching)
                : prettyIfAny;
        tvLocation.setText(getString(R.string.cw_location, showing));
        lastPlace = prettyIfAny;

        if (prettyIfAny == null || prettyIfAny.trim().isEmpty()) {
            new Thread(() -> {
                String place = getPlaceName(lat, lon);
                runOnUiThread(() -> {
                    lastPlace = place;
                    tvLocation.setText(getString(R.string.cw_location,
                            place == null ? getString(R.string.cw_location_unknown) : place));
                });
            }).start();
        }
    }

    private final BroadcastReceiver defaultChangedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!HomeLocationsActivity.ACTION_DEFAULT_CHANGED.equals(intent.getAction())) return;
            if (launchedWithExplicitPlace) return;

            boolean hasDefault = intent.getBooleanExtra("hasDefault", false);
            if (hasDefault) {
                double lat = intent.getDoubleExtra("lat", Double.NaN);
                double lon = intent.getDoubleExtra("lon", Double.NaN);
                if (!Double.isNaN(lat) && !Double.isNaN(lon)) {
                    lastLat = lat;
                    lastLon = lon;
                    String pretty = intent.getStringExtra("pretty");
                    updateLocationNameAsync(lat, lon, pretty);
                    fetchOnlineWeather(lat, lon, pretty);
                }
            } else {
                revertToDeviceLocation();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_current_weather);

        setupBottomBar(R.id.tab_current);

        tvTemp = findViewById(R.id.tvTemp);
        tvHumidity = findViewById(R.id.tvHumidity);
        tvCondition = findViewById(R.id.tvCondition);
        tvMode = findViewById(R.id.tvMode);
        tvHint = findViewById(R.id.tvHint);
        tvLocation = findViewById(R.id.tvLocation);
        tvMin = findViewById(R.id.tvMin);
        tvMax = findViewById(R.id.tvMax);
        tvFeelsLike = findViewById(R.id.tvFeelsLike);
        tvSunrise = findViewById(R.id.tvSunrise);
        tvSunset = findViewById(R.id.tvSunset);

        tvAqi = findViewById(R.id.tvAqi);
        tvAqiCategory = findViewById(R.id.tvAqiCategory);
        tvGusts = findViewById(R.id.tvGusts);
        tvWindArrow = findViewById(R.id.tvWindArrow);

        tvUvValue = findViewById(R.id.tvUvValue);
        tvUvCategory = findViewById(R.id.tvUvCategory);

        sm = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sm != null) {
            light = sm.getDefaultSensor(Sensor.TYPE_LIGHT);
        }
        if (light == null) {
            Toast.makeText(this, getString(R.string.cw_no_light), Toast.LENGTH_SHORT).show();
        }

        launchedWithExplicitPlace = getIntent() != null
                && getIntent().hasExtra("lat") && getIntent().hasExtra("lon");

        if (launchedWithExplicitPlace) {
            lastLat = getIntent().getDoubleExtra("lat", 0);
            lastLon = getIntent().getDoubleExtra("lon", 0);
            lastPlace = getIntent().getStringExtra("place");
            tvLocation.setText(getString(R.string.cw_location,
                    lastPlace == null ? getString(R.string.cw_location_unknown) : lastPlace));
            fetchOnlineWeather(lastLat, lastLon, lastPlace);
            applyUIMode();
            return;
        }

        PlaceStore ps = new PlaceStore(this);
        double[] def = ps.getDefaultLatLon();
        if (def != null) {
            lastLat = def[0];
            lastLon = def[1];
            updateLocationNameAsync(lastLat, lastLon, null);
            fetchOnlineWeather(lastLat, lastLon, null);
        } else {
            revertToDeviceLocation();
        }
        applyUIMode();
    }

    @Override
    protected void onResume() {
        super.onResume();
        setupBottomBar(R.id.tab_current);
        registerReceiver(defaultChangedReceiver, new IntentFilter(HomeLocationsActivity.ACTION_DEFAULT_CHANGED));

        if (!launchedWithExplicitPlace) {
            PlaceStore ps = new PlaceStore(this);
            double[] def = ps.getDefaultLatLon();
            if (def != null) {
                if (lastLat == null || Math.abs(lastLat - def[0]) > 1e-6 || Math.abs(lastLon - def[1]) > 1e-6) {
                    lastLat = def[0];
                    lastLon = def[1];
                    updateLocationNameAsync(lastLat, lastLon, null);
                    fetchOnlineWeather(lastLat, lastLon, null);
                }
            } else {
                revertToDeviceLocation();
            }
        }

        if (sm != null && light != null) {
            sm.registerListener(this, light, SensorManager.SENSOR_DELAY_NORMAL);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            unregisterReceiver(defaultChangedReceiver);
        } catch (Exception ignore) {
        }
        if (sm != null) sm.unregisterListener(this);
    }

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

    private void revertToDeviceLocation() {
        lastLat = null;
        lastLon = null;
        lastPlace = null;
        tvLocation.setText(getString(R.string.cw_location, getString(R.string.cw_fetching)));
        requestLocationAndFetch();
    }

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
            fallbackMock();
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
            fallbackMock();
        }
    }

    private void onGotLocation(Location loc) {
        lastLat = loc.getLatitude();
        lastLon = loc.getLongitude();

        new Thread(() -> {
            String place = getPlaceName(lastLat, lastLon);
            runOnUiThread(() -> tvLocation.setText(
                    getString(R.string.cw_location,
                            place == null ? getString(R.string.cw_location_unknown) : place)));
            lastPlace = place;
        }).start();

        fetchOnlineWeather(lastLat, lastLon, lastPlace);
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

    private void fetchOnlineWeather(Double lat, Double lon, String place) {
        if (lat == null || lon == null) {
            fallbackMock();
            return;
        }

        tvLocation.setText(getString(R.string.cw_location,
                place == null ? getString(R.string.cw_fetching) : place));

        new Thread(() -> {
            try {
                String url = "https://api.open-meteo.com/v1/forecast?latitude=" + lat +
                        "&longitude=" + lon +
                        "&current=temperature_2m,relative_humidity_2m,weather_code,apparent_temperature," +
                        "wind_speed_10m,wind_direction_10m,wind_gusts_10m" +
                        "&daily=temperature_2m_min,temperature_2m_max,sunrise,sunset" +
                        "&timezone=auto";

                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setRequestMethod("GET");

                int code = conn.getResponseCode();
                if (code != HttpURLConnection.HTTP_OK) throw new RuntimeException("HTTP " + code);

                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();
                conn.disconnect();

                JSONObject root = new JSONObject(sb.toString());
                JSONObject cur = root.getJSONObject("current");
                final int tempC = (int) Math.round(cur.getDouble("temperature_2m"));

                double hVal = cur.optDouble("relative_humidity_2m", Double.NaN);
                final int humidity = Double.isNaN(hVal) ? 50 : (int) Math.round(hVal);

                final int wcode = cur.has("weather_code") ? cur.getInt("weather_code") : 0;
                final String cond = mapWeatherCode(wcode);

                Integer feelsLikeC = null;
                if (cur.has("apparent_temperature") && !cur.isNull("apparent_temperature")) {
                    double app = cur.optDouble("apparent_temperature", Double.NaN);
                    if (!Double.isNaN(app)) feelsLikeC = (int) Math.round(app);
                }

                final double gustMs = cur.optDouble("wind_gusts_10m", Double.NaN);
                final double windDir = cur.optDouble("wind_direction_10m", Double.NaN);
                final int gustKmh = Double.isNaN(gustMs) ? -1 : (int) Math.round(gustMs * 3.6);
                final String dirText = Double.isNaN(windDir) ? "—" : degToCompass(windDir);

                JSONObject daily = root.optJSONObject("daily");
                Integer minC = null, maxC = null;
                String sunriseStr = null, sunsetStr = null;
                if (daily != null) {
                    try {
                        minC = (int) Math.round(daily.getJSONArray("temperature_2m_min").getDouble(0));
                        maxC = (int) Math.round(daily.getJSONArray("temperature_2m_max").getDouble(0));
                    } catch (Exception ignore) {
                    }
                    try {
                        JSONArray sra = daily.optJSONArray("sunrise");
                        JSONArray ssa = daily.optJSONArray("sunset");
                        if (sra != null && sra.length() > 0)
                            sunriseStr = formatClockLocal(sra.optString(0, null));
                        if (ssa != null && ssa.length() > 0)
                            sunsetStr = formatClockLocal(ssa.optString(0, null));
                    } catch (Exception ignore) {
                    }
                }
                final Integer fMin = minC, fMax = maxC;
                final Integer fFeels = feelsLikeC;
                final String fSunrise = sunriseStr, fSunset = sunsetStr;

                fetchAqiAsync(lat, lon);
                fetchUvAsync(lat, lon);

                runOnUiThread(() -> {
                    tvTemp.setText(getString(R.string.cw_temp, tempC));
                    tvHumidity.setText(getString(R.string.cw_humidity, humidity));
                    tvCondition.setText(getString(R.string.cw_condition, cond));

                    if (fMin != null && fMax != null) {
                        tvMin.setVisibility(TextView.VISIBLE);
                        tvMax.setVisibility(TextView.VISIBLE);
                        tvMin.setText(String.format(Locale.getDefault(), "Min: %d°", fMin));
                        tvMax.setText(String.format(Locale.getDefault(), "Max: %d°", fMax));
                    } else {
                        tvMin.setVisibility(TextView.GONE);
                        tvMax.setVisibility(TextView.GONE);
                    }

                    if (fFeels != null) {
                        tvFeelsLike.setText(getString(R.string.cw_feels_like_fmt, fFeels + "°C"));
                    } else {
                        tvFeelsLike.setText(R.string.cw_feels_like_placeholder);
                    }
                    tvFeelsLike.setVisibility(TextView.VISIBLE);

                    if (fSunrise != null) {
                        tvSunrise.setText(getString(R.string.cw_sunrise_fmt, fSunrise));
                    } else {
                        tvSunrise.setText(R.string.cw_sunrise_placeholder);
                    }
                    tvSunrise.setVisibility(TextView.VISIBLE);

                    if (fSunset != null) {
                        tvSunset.setText(getString(R.string.cw_sunset_fmt, fSunset));
                    } else {
                        tvSunset.setText(R.string.cw_sunset_placeholder);
                    }
                    tvSunset.setVisibility(TextView.VISIBLE);

                    if (gustKmh >= 0) {
                        tvGusts.setText("Gusts: " + gustKmh + " km/h");
                    } else {
                        tvGusts.setText("Gusts: —");
                    }
                    tvWindArrow.setText(arrowForDirection(dirText));

                    Toast.makeText(this, getString(R.string.cw_refreshed), Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    Toast.makeText(this, getString(R.string.cw_error), Toast.LENGTH_SHORT).show();
                    fallbackMock();
                });
            }
        }).start();
    }

    private void fetchAqiAsync(Double lat, Double lon) {
        new Thread(() -> {
            try {
                String url = "https://air-quality-api.open-meteo.com/v1/air-quality?latitude=" + lat +
                        "&longitude=" + lon + "&hourly=us_aqi&timezone=auto";
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setRequestMethod("GET");
                if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) throw new RuntimeException("AQI HTTP");
                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();
                conn.disconnect();

                JSONObject root = new JSONObject(sb.toString());
                JSONArray aqiArr = root.getJSONObject("hourly").getJSONArray("us_aqi");
                final int aqi = (int) Math.round(aqiArr.getDouble(0));

                runOnUiThread(() -> setAqiViews(aqi));
            } catch (Exception ignore) {
                runOnUiThread(() -> setAqiViews(-1));
            }
        }).start();
    }

    private void fetchUvAsync(Double lat, Double lon) {
        new Thread(() -> {
            try {
                String url = "https://api.open-meteo.com/v1/forecast?latitude=" + lat +
                        "&longitude=" + lon +
                        "&hourly=uv_index" +
                        "&daily=uv_index_max" +
                        "&timezone=auto";
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setRequestMethod("GET");
                if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) throw new RuntimeException("UV HTTP");
                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();
                conn.disconnect();

                JSONObject root = new JSONObject(sb.toString());

                String tzId = root.optString("timezone", TimeZone.getDefault().getID());
                TimeZone tz = TimeZone.getTimeZone(tzId);

                JSONObject hourly = root.getJSONObject("hourly");
                JSONArray times = hourly.getJSONArray("time");
                JSONArray uvArr = hourly.getJSONArray("uv_index");

                SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US);
                fmt.setTimeZone(tz);
                String nowStr = fmt.format(new Date());

                int idx = -1;
                for (int i = 0; i < times.length(); i++) {
                    if (nowStr.equals(times.getString(i))) {
                        idx = i;
                        break;
                    }
                }
                if (idx < 0) {
                    long nowMs = fmt.parse(nowStr, new ParsePosition(0)).getTime();
                    long bestDiff = Long.MAX_VALUE;
                    int bestIdx = -1;
                    for (int i = 0; i < times.length(); i++) {
                        Date d = fmt.parse(times.getString(i), new ParsePosition(0));
                        if (d == null) continue;
                        long diff = Math.abs(d.getTime() - nowMs);
                        if (diff < bestDiff) {
                            bestDiff = diff;
                            bestIdx = i;
                        }
                    }
                    idx = bestIdx;
                }

                double uv = Double.NaN;
                if (idx >= 0 && idx < uvArr.length()) {
                    double v = uvArr.optDouble(idx, Double.NaN);
                    if (!Double.isNaN(v)) uv = v;
                }

                if (Double.isNaN(uv)) {
                    JSONObject daily = root.optJSONObject("daily");
                    if (daily != null) {
                        JSONArray uvMax = daily.optJSONArray("uv_index_max");
                        uv = (uvMax != null && uvMax.length() > 0) ? uvMax.optDouble(0, 0.0) : 0.0;
                    } else {
                        uv = 0.0;
                    }
                }

                final double fUv = uv;
                runOnUiThread(() -> setUvViews(fUv));
            } catch (Exception ignore) {
                runOnUiThread(() -> setUvViews(-1));
            }
        }).start();
    }

    private void setAqiViews(int aqi) {
        if (aqi < 0) {
            tvAqi.setText("AQI: —");
            tvAqiCategory.setText("—");
            tvAqiCategory.setTextColor(Color.WHITE);
            return;
        }
        tvAqi.setText("AQI: " + aqi);
        String cat;
        int color;
        if (aqi <= 50) {
            cat = "Good";
            color = Color.parseColor("#2ecc71");
        } else if (aqi <= 100) {
            cat = "Moderate";
            color = Color.parseColor("#f1c40f");
        } else if (aqi <= 150) {
            cat = "Unhealthy (SG)";
            color = Color.parseColor("#e67e22");
        } else if (aqi <= 200) {
            cat = "Unhealthy";
            color = Color.parseColor("#e74c3c");
        } else if (aqi <= 300) {
            cat = "Very Unhealthy";
            color = Color.parseColor("#8e44ad");
        } else {
            cat = "Hazardous";
            color = Color.parseColor("#7f0000");
        }
        tvAqiCategory.setText(cat);
        tvAqiCategory.setTextColor(color);
    }

    private void setUvViews(double uvVal) {
        if (tvUvValue == null || tvUvCategory == null) return;

        if (uvVal < 0) {
            tvUvValue.setText("UV: —");
            tvUvCategory.setText("—");
            return;
        }

        int uvRounded = (int) Math.round(uvVal);
        tvUvValue.setText("UV: " + uvRounded);

        String cat;
        int color;
        if (uvVal <= 2) {
            cat = "Low";
            color = Color.parseColor("#2ecc71");
        } else if (uvVal <= 5) {
            cat = "Moderate";
            color = Color.parseColor("#f1c40f");
        } else if (uvVal <= 7) {
            cat = "High";
            color = Color.parseColor("#e67e22");
        } else {
            cat = "Very High";
            color = Color.parseColor("#e74c3c");
        }

        tvUvCategory.setText(cat);
        tvUvValue.setTextColor(color);
        tvUvCategory.setTextColor(color);
    }

    private static String degToCompass(double deg) {
        String[] dirs = {"N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
                "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"};
        int i = (int) Math.round(((deg % 360) / 22.5));
        return dirs[i % 16];
    }

    private static String arrowForDirection(String dir) {
        switch (dir) {
            case "N":
                return "⬆️";
            case "NE":
            case "NNE":
            case "ENE":
                return "↗️";
            case "E":
                return "➡️";
            case "SE":
            case "ESE":
            case "SSE":
                return "↘️";
            case "S":
                return "⬇️";
            case "SW":
            case "WSW":
            case "SSW":
                return "↙️";
            case "W":
                return "⬅️";
            case "NW":
            case "WNW":
            case "NNW":
                return "↖️";
            default:
                return "🧭";
        }
    }

    private String formatClockLocal(String apiValue) {
        if (apiValue == null) return null;
        SimpleDateFormat parser = new SimpleDateFormat(
                apiValue.length() > 16 ? "yyyy-MM-dd'T'HH:mm:ss" : "yyyy-MM-dd'T'HH:mm",
                Locale.US
        );
        parser.setTimeZone(TimeZone.getDefault());
        Date parsed = parser.parse(apiValue, new ParsePosition(0));
        if (parsed == null) return null;
        SimpleDateFormat out = new SimpleDateFormat("hh:mm a", Locale.getDefault());
        out.setTimeZone(TimeZone.getDefault());
        return out.format(parsed);
    }

    private String mapWeatherCode(int code) {
        if (code == 0) return "Sunny";
        if (code == 1 || code == 2 || code == 3) return "Cloudy";
        if (code >= 45 && code <= 48) return "Cloudy";
        if ((code >= 51 && code <= 67) || (code >= 80 && code <= 82)) return "Rain";
        if (code >= 95) return "Thunderstorm";
        if (code >= 71 && code <= 77) return "Snow";
        return "Cloudy";
    }

    private void fallbackMock() {
        WeatherData d = repo.getCurrentWeather();
        tvTemp.setText(getString(R.string.cw_temp, d.tempC));
        tvHumidity.setText(getString(R.string.cw_humidity, d.humidity));
        tvCondition.setText(getString(R.string.cw_condition, d.condition));
        if (tvMin != null) tvMin.setVisibility(TextView.GONE);
        if (tvMax != null) tvMax.setVisibility(TextView.GONE);

        if (tvFeelsLike != null) {
            tvFeelsLike.setText(R.string.cw_feels_like_placeholder);
            tvFeelsLike.setVisibility(TextView.VISIBLE);
        }
        if (tvSunrise != null) {
            tvSunrise.setText(R.string.cw_sunrise_placeholder);
            tvSunrise.setVisibility(TextView.VISIBLE);
        }
        if (tvSunset != null) {
            tvSunset.setText(R.string.cw_sunset_placeholder);
            tvSunset.setVisibility(TextView.VISIBLE);
        }

        if (tvGusts != null) tvGusts.setText("Gusts: —");
        if (tvAqi != null) tvAqi.setText("AQI: —");
        if (tvAqiCategory != null) tvAqiCategory.setText("—");
        if (tvUvValue != null) tvUvValue.setText("UV: —");
        if (tvUvCategory != null) tvUvCategory.setText("—");

        if (lastPlace == null)
            tvLocation.setText(getString(R.string.cw_location, getString(R.string.cw_location_unknown)));
    }

    private void applyUIMode() {
        ImageView bgImage = findViewById(R.id.bgImage);
        if (bgImage != null) {
            bgImage.setImageResource(isDay ? R.drawable.light : R.drawable.dark_clouds);
        }

        final int baseColor = isDay ? Color.BLACK : Color.WHITE;

        tvTemp.setTextColor(baseColor);
        tvHumidity.setTextColor(baseColor);
        tvCondition.setTextColor(baseColor);
        tvMode.setTextColor(baseColor);
        tvHint.setTextColor(baseColor);
        tvLocation.setTextColor(baseColor);
        if (tvMin != null) tvMin.setTextColor(baseColor);
        if (tvMax != null) tvMax.setTextColor(baseColor);
        if (tvFeelsLike != null) tvFeelsLike.setTextColor(baseColor);
        if (tvSunrise != null) tvSunrise.setTextColor(baseColor);
        if (tvSunset != null) tvSunset.setTextColor(baseColor);

        if (tvAqi != null) tvAqi.setTextColor(baseColor);
        if (tvGusts != null) tvGusts.setTextColor(baseColor);
        if (tvWindArrow != null) tvWindArrow.setTextColor(baseColor);

        tvMode.setText(isDay ? "Light" : "Dark");
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_LIGHT) return;

        long now = android.os.SystemClock.elapsedRealtime();
        long since = now - lastLightSampleMs;
        if (since < LIGHT_SAMPLE_MIN_MS) return;
        lastLightSampleMs = now;

        float raw = event.values[0];
        raw = Math.max(LUX_MIN, Math.min(LUX_MAX, raw));

        float base = (luxAvg == 0f) ? raw : luxAvg;
        float limited = raw;
        if (raw > base * LUX_JUMP_MULT) {
            limited = base + (raw - base) * 0.25f;
        } else if (raw < base / LUX_JUMP_MULT) {
            limited = base + (raw - base) * 0.25f;
        }

        float dtSec = Math.max(0.001f, since / 1000f);
        float alpha = dtSec / (SMOOTH_TAU_S + dtSec);
        luxAvg = (luxAvg == 0f) ? limited : (luxAvg + alpha * (limited - luxAvg));

        if (isDay) {
            if (luxAvg <= LUX_NIGHT_ON) {
                if (dayTriggerAtMs == null) dayTriggerAtMs = now;
                if (now - dayTriggerAtMs >= MODE_DWELL_DARK_MS) {
                    isDay = false;
                    dayTriggerAtMs = nightTriggerAtMs = null;
                    applyUIMode();
                    setAppBrightness(DARK_BRIGHTNESS);
                }
            } else {
                dayTriggerAtMs = null;
            }
        } else {
            if (luxAvg >= LUX_DAY_ON) {
                if (nightTriggerAtMs == null) nightTriggerAtMs = now;
                if (now - nightTriggerAtMs >= MODE_DWELL_LIGHT_MS) {
                    isDay = true;
                    dayTriggerAtMs = nightTriggerAtMs = null;
                    setAppBrightness(BRIGHTNESS_DEFAULT);
                    applyUIMode();
                }
            } else {
                nightTriggerAtMs = null;
            }
        }
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
                Toast.makeText(this, getString(R.string.cw_error), Toast.LENGTH_SHORT).show();
                fallbackMock();
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        moveTaskToBack(true);
    }
}
