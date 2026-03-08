// ForecastItem.java
package com.example.miniweathertracker;

public class ForecastItem {
    public final String label; // e.g., Mon, Tue
    public final float tempC;  // daily max °C

    public ForecastItem(String label, float tempC) {
        this.label = label;
        this.tempC = tempC;
    }
}
