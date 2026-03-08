package com.example.miniweathertracker;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.GeomagneticField;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.view.Surface;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import com.google.android.material.bottomnavigation.BottomNavigationView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Locale;

public class WindCompassActivity extends AppCompatActivity implements SensorEventListener {

    private static final int REQ_LOC_COMPASS = 72;

    // prefs for current-location label cache (same scheme as other screens)
    private static final String WEATHER_PREFS = "weather_prefs";
    private static final String KEY_LAST_LOCATION_CITY = "last_location_city";

    // PlaceStore (default location)
    private static final String PLACES_PREFS = "places_store";
    private static final String KEY_DEF_LAT = "default_lat";
    private static final String KEY_DEF_LON = "default_lon";
    private static final String KEY_DEF_PRETTY = "default_pretty";

    private SharedPreferences weatherPrefs;
    private SharedPreferences placesPrefs;
    private SharedPreferences.OnSharedPreferenceChangeListener placesListener;
    private PlaceStore placeStore;

    // sensors / UI
    private SensorManager sm;
    private Sensor accel, magnet, rotVec;

    private CompassView compassView;
    private TextView tvHeading, tvWind, tvLoc;

    private final float[] grav = new float[3];
    private final float[] mag  = new float[3];
    private boolean haveGrav = false, haveMag = false, haveRotVec = false;

    private float azFiltered = Float.NaN;
    private static final float AZ_ALPHA = 0.12f;

    private Double lastLat, lastLon, lastAlt;
    private float declinationDeg = 0f;

    @SuppressLint("SetTextI18n")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_wind_compass);

        compassView = findViewById(R.id.compassView);
        tvHeading   = findViewById(R.id.tvHeading);
        tvWind      = findViewById(R.id.tvWind);
        tvLoc       = findViewById(R.id.tvLoc);

        // sensors
        sm = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sm != null) {
            rotVec  = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
            accel   = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            magnet  = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        }

        setupBottomBar(R.id.tab_compass);

        // prefs
        weatherPrefs = getSharedPreferences(WEATHER_PREFS, MODE_PRIVATE);
        placeStore   = new PlaceStore(this);
        placesPrefs  = getSharedPreferences(PLACES_PREFS, MODE_PRIVATE);

        // live-switch when default is set/cleared
        placesListener = (sp, key) -> {
            if (KEY_DEF_LAT.equals(key) || KEY_DEF_LON.equals(key) || KEY_DEF_PRETTY.equals(key)) {
                applySourcePolicy();
            }
        };

        // if launched with explicit coords, honor them
        if (getIntent() != null && getIntent().hasExtra("lat") && getIntent().hasExtra("lon")) {
            lastLat = getIntent().getDoubleExtra("lat", 0);
            lastLon = getIntent().getDoubleExtra("lon", 0);
            lastAlt = 0.0;
            String place = getIntent().getStringExtra("place");
            tvLoc.setText("Location: " + (place == null ? "—" : place));
            updateDeclination();
            fetchWind(lastLat, lastLon);
            return;
        }

        // initial render
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

    @Override protected void onResume() {
        super.onResume();
        setupBottomBar(R.id.tab_compass);
        applySourcePolicy();

        if (sm != null) {
            if (rotVec != null) {
                sm.registerListener(this, rotVec, SensorManager.SENSOR_DELAY_GAME);
                haveRotVec = true;
            } else {
                haveRotVec = false;
                if (accel != null)  sm.registerListener(this, accel,  SensorManager.SENSOR_DELAY_GAME);
                if (magnet != null) sm.registerListener(this, magnet, SensorManager.SENSOR_DELAY_GAME);
            }
        }
    }

    @Override protected void onPause() {
        super.onPause();
        if (sm != null) sm.unregisterListener(this);
    }

    /* --------------------- Default vs Current Location --------------------- */

    @SuppressLint("SetTextI18n")
    private void applySourcePolicy() {
        // 1) If PlaceStore has a default → show label and fetch for those coords
        if (placeStore.hasDefault()) {
            double[] latlon = placeStore.getDefaultLatLon();
            if (latlon != null) {
                String pretty = placeStore.getDefaultPrettyOrNull();
                if (!isEmpty(pretty)) {
                    tvLoc.setText("Location: " + pretty.trim());
                } else {
                    // Try to derive from saved list; else reverse-geocode in background
                    String derived = derivePrettyFromSaved(latlon[0], latlon[1]);
                    if (!isEmpty(derived)) tvLoc.setText("Location: " + derived);
                    else reverseGeocodeAndSet(latlon[0], latlon[1]);
                }
                lastLat = latlon[0];
                lastLon = latlon[1];
                lastAlt = 0.0;
                updateDeclination();
                fetchWind(lastLat, lastLon);
                return;
            }
        }

        // 2) No default → show cached current-location label and fetch via device location
        String cached = weatherPrefs.getString(KEY_LAST_LOCATION_CITY, "");
        tvLoc.setText("Location: " + cached);
        requestLocation();
    }

    private String derivePrettyFromSaved(double lat, double lon) {
        List<PlaceStore.Place> all = placeStore.getAll();
        for (PlaceStore.Place p : all) {
            if (same(p.lat, lat) && same(p.lon, lon)) {
                if (!isEmpty(p.pretty)) return p.pretty.trim();
                if (!isEmpty(p.name))   return p.name.trim();
            }
        }
        return null;
    }

    @SuppressLint("SetTextI18n")
    private void reverseGeocodeAndSet(double lat, double lon) {
        new Thread(() -> {
            String name = geocodeName(lat, lon);
            if (name == null) return;
            runOnUiThread(() -> tvLoc.setText("Location: " + name));
        }).start();
    }

    /* ----------------------------- Location ----------------------------- */

    private boolean hasFine() {
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasCoarse() {
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestLocation() {
        if (!(hasFine() || hasCoarse())) {
            ActivityCompat.requestPermissions(
                    this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION},
                    REQ_LOC_COMPASS);
            return;
        }

        LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (lm == null) return;

        Location best;
        best = null;
        try {
            if (hasFine()) {
                Location l = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            }
        } catch (SecurityException ignore) {}
        try {
            if (hasCoarse() || hasFine()) {
                Location l = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            }
        } catch (SecurityException ignore) {}

        if (best != null) { onGotLocation(best); return; }

        try {
            if (hasFine()) {
                lm.getCurrentLocation(LocationManager.GPS_PROVIDER, null, getMainExecutor(), this::onGotLocation);
            } else if (hasCoarse()) {
                lm.getCurrentLocation(LocationManager.NETWORK_PROVIDER, null, getMainExecutor(), this::onGotLocation);
            }
        } catch (SecurityException ignore) {}
    }

    @SuppressLint("SetTextI18n")
    private void onGotLocation(Location loc) {
        if (loc == null) return;
        lastLat = loc.getLatitude();
        lastLon = loc.getLongitude();
        lastAlt = loc.hasAltitude() ? loc.getAltitude() : 0.0;
        updateDeclination();
        // label + cache
        new Thread(() -> {
            String name = geocodeName(lastLat, lastLon);
            if (name == null) name = getString(R.string.cw_location_unknown);
            String finalName = name;
            weatherPrefs.edit().putString(KEY_LAST_LOCATION_CITY, finalName).apply();
            runOnUiThread(() -> tvLoc.setText("Location: " + finalName));
        }).start();
        fetchWind(lastLat, lastLon);
    }

    private String geocodeName(double lat, double lon) {
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

    /* ---------------------------- Wind fetch ---------------------------- */

    private void fetchWind(double lat, double lon) {
        new Thread(() -> {
            try {
                String url = "https://api.open-meteo.com/v1/forecast?latitude=" + lat +
                        "&longitude=" + lon +
                        "&current=wind_speed_10m,wind_direction_10m&wind_speed_unit=kmh&timezone=auto";

                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                if (c.getResponseCode() != 200) throw new RuntimeException("HTTP " + c.getResponseCode());

                BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream()));
                StringBuilder sb = new StringBuilder(); String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close(); c.disconnect();

                JSONObject cur = new JSONObject(sb.toString()).getJSONObject("current");
                final int windDir = cur.optInt("wind_direction_10m", 0);
                final int windSpd = (int)Math.round(cur.optDouble("wind_speed_10m", 0));

                runOnUiThread(() -> {
                    compassView.setWindDirection(windDir); // meteorological "from" degrees
                    tvWind.setText(getString(R.string.wind_fmt, windSpd, windDir));
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, R.string.cw_error, Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    /* ---------------------------- Heading (sensors) ---------------------------- */

    @Override
    public void onSensorChanged(SensorEvent e) {
        if (e.sensor.getType() == Sensor.TYPE_ROTATION_VECTOR) {
            float[] R = new float[9];
            SensorManager.getRotationMatrixFromVector(R, e.values);
            updateHeadingFromRotationMatrix(R);
            return;
        }

        if (e.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            System.arraycopy(e.values, 0, grav, 0, 3);
            haveGrav = true;
        } else if (e.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
            System.arraycopy(e.values, 0, mag, 0, 3);
            haveMag = true;
        }

        if (!haveRotVec && haveGrav && haveMag) {
            float[] R = new float[9];
            float[] I = new float[9];
            if (SensorManager.getRotationMatrix(R, I, grav, mag)) {
                updateHeadingFromRotationMatrix(R);
            }
        }
    }

    /** Converts rotation matrix to TRUE heading, with display-rotation + declination. */
    private void updateHeadingFromRotationMatrix(float[] R) {
        int rotation = (getWindowManager().getDefaultDisplay() != null)
                ? getWindowManager().getDefaultDisplay().getRotation()
                : Surface.ROTATION_0;

        int axisX, axisY;
        switch (rotation) {
            case Surface.ROTATION_90:  axisX = SensorManager.AXIS_Y;        axisY = SensorManager.AXIS_MINUS_X; break;
            case Surface.ROTATION_180: axisX = SensorManager.AXIS_MINUS_X;  axisY = SensorManager.AXIS_MINUS_Y; break;
            case Surface.ROTATION_270: axisX = SensorManager.AXIS_MINUS_Y;  axisY = SensorManager.AXIS_X;       break;
            case Surface.ROTATION_0:
            default:                   axisX = SensorManager.AXIS_X;        axisY = SensorManager.AXIS_Y;
        }

        float[] outR = new float[9];
        SensorManager.remapCoordinateSystem(R, axisX, axisY, outR);

        float[] orient = new float[3];
        SensorManager.getOrientation(outR, orient);
        float azMag = (float) Math.toDegrees(orient[0]);
        if (azMag < 0) azMag += 360f;

        // TRUE north
        float azTrue = (azMag + declinationDeg + 360f) % 360f;

        if (Float.isNaN(azFiltered)) azFiltered = azTrue;
        else azFiltered = smoothAngle(azFiltered, azTrue, AZ_ALPHA);

        compassView.setHeading(azFiltered);
        tvHeading.setText("Heading: " + Math.round(azFiltered) + "°");
    }

    private static float smoothAngle(float current, float target, float alpha) {
        float diff = ((target - current + 540f) % 360f) - 180f;
        return (current + alpha * diff + 360f) % 360f;
    }

    private void updateDeclination() {
        if (lastLat == null || lastLon == null) { declinationDeg = 0f; return; }
        GeomagneticField gf = new GeomagneticField(
                lastLat.floatValue(),
                lastLon.floatValue(),
                lastAlt == null ? 0f : lastAlt.floatValue(),
                System.currentTimeMillis());
        declinationDeg = gf.getDeclination();
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    /* -------------------------- Bottom navigation -------------------------- */

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

    /* ---------------------------- Permissions ---------------------------- */

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_LOC_COMPASS) {
            boolean granted = false;
            for (int g : grantResults) { if (g == PackageManager.PERMISSION_GRANTED) { granted = true; break; } }
            if (granted) requestLocation();
        }
    }

    /* ------------------------------- utils ------------------------------- */

    private static boolean same(double a, double b) { return Math.abs(a - b) < 1e-6; }
    private static boolean isEmpty(String s) { return s == null || s.trim().isEmpty(); }
    @Override
    public void onBackPressed() {
        // From any bottom-tab page, Back should exit to the phone’s Home.
        super.onBackPressed();
        moveTaskToBack(true);
    }

}
