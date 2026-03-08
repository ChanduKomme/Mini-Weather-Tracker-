package com.example.miniweathertracker;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class PlaceStore {

    public static class Place {
        public String name;
        public String pretty;
        public double lat;
        public double lon;
    }

    private static final String PREFS = "places_store";
    private static final String KEY_LIST = "saved_json";
    private static final String KEY_DEF_LAT = "default_lat";
    private static final String KEY_DEF_LON = "default_lon";
    private static final String KEY_DEF_PRETTY = "default_pretty"; // optional

    private final SharedPreferences prefs;

    public PlaceStore(Context ctx) {
        this.prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /* -------- saved list -------- */

    public List<Place> getAll() {
        String json = prefs.getString(KEY_LIST, "[]");
        List<Place> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Place p = new Place();
                p.name   = o.optString("name", "");
                p.pretty = o.optString("pretty", "");
                p.lat    = o.optDouble("lat", Double.NaN);
                p.lon    = o.optDouble("lon", Double.NaN);
                if (!Double.isNaN(p.lat) && !Double.isNaN(p.lon)) out.add(p);
            }
        } catch (Exception ignore) { }
        return out;
    }

    public void add(Place p) {
        if (p == null || TextUtils.isEmpty(p.pretty)) return;
        List<Place> list = getAll();
        for (Place e : list) {
            if (same(e.lat, p.lat) && same(e.lon, p.lon)) return;
        }
        list.add(p);
        save(list);
    }

    public void remove(double lat, double lon) {
        List<Place> list = getAll();
        for (int i = list.size() - 1; i >= 0; i--) {
            if (same(list.get(i).lat, lat) && same(list.get(i).lon, lon)) list.remove(i);
        }
        save(list);
    }

    private void save(List<Place> list) {
        try {
            JSONArray arr = new JSONArray();
            for (Place p : list) {
                JSONObject o = new JSONObject();
                o.put("name", p.name == null ? "" : p.name);
                o.put("pretty", p.pretty == null ? "" : p.pretty);
                o.put("lat", p.lat);
                o.put("lon", p.lon);
                arr.put(o);
            }
            prefs.edit().putString(KEY_LIST, arr.toString()).apply();
        } catch (Exception ignore) { }
    }

    /* -------- default place (set / unset / get) -------- */

    public boolean isDefault(double lat, double lon) {
        if (!prefs.contains(KEY_DEF_LAT) || !prefs.contains(KEY_DEF_LON)) return false;
        double dLat = Double.longBitsToDouble(prefs.getLong(KEY_DEF_LAT, 0));
        double dLon = Double.longBitsToDouble(prefs.getLong(KEY_DEF_LON, 0));
        return same(dLat, lat) && same(dLon, lon);
    }

    public void setDefault(double lat, double lon) {
        prefs.edit()
                .putLong(KEY_DEF_LAT, Double.doubleToLongBits(lat))
                .putLong(KEY_DEF_LON, Double.doubleToLongBits(lon))
                .apply();
    }

    public void clearDefault() {
        prefs.edit()
                .remove(KEY_DEF_LAT)
                .remove(KEY_DEF_LON)
                .remove(KEY_DEF_PRETTY)
                .apply();
    }

    /** NEW: is there a default stored? */
    public boolean hasDefault() {
        return prefs.contains(KEY_DEF_LAT) && prefs.contains(KEY_DEF_LON);
    }

    /** NEW: get default lat/lon, or null if none. */
    public double[] getDefaultLatLon() {
        if (!hasDefault()) return null;
        double lat = Double.longBitsToDouble(prefs.getLong(KEY_DEF_LAT, 0));
        double lon = Double.longBitsToDouble(prefs.getLong(KEY_DEF_LON, 0));
        return new double[]{lat, lon};
    }

    /** Optional: retrieve a stored pretty label if you decide to save it. */
    public String getDefaultPrettyOrNull() {
        return prefs.getString(KEY_DEF_PRETTY, null);
    }

    private boolean same(double a, double b) { return Math.abs(a - b) < 1e-6; }
}
