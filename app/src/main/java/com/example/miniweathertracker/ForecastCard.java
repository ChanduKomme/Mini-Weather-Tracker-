// ForecastCard.java
package com.example.miniweathertracker;

/** Simple mutable DTO used by ForecastGraphView. */
public class ForecastCard {
    public String dow;  // e.g., "Mon"
    public int dayNum;
    public float tMax;
    public float tMin;
    public int precip;  // 0..100 or -1 for "–"
    public int wcode;   // Open-Meteo weather code

    public ForecastCard(String dow, int dayNum, float tMax, float tMin, int precip, int wcode) {
        this.dow = dow;
        this.dayNum = dayNum;
        this.tMax = tMax;
        this.tMin = tMin;
        this.precip = precip;
        this.wcode = wcode;
    }
}
