package com.example.miniweathertracker;

public class HourlyItem {
    public final String timeLabel;  // "4 AM"
    public final int temp;          // °C or °F (rounded from API)
    public final int feels;         // RealFeel (rounded from API)
    public final int precip;        // %
    public final int wcode;         // Open-Meteo weathercode
    public final boolean isNight;   // true when hourly is_day == 0

    public HourlyItem(String timeLabel, int temp, int feels, int precip, int wcode, boolean isNight) {
        this.timeLabel = timeLabel;
        this.temp = temp;
        this.feels = feels;
        this.precip = precip;
        this.wcode = wcode;
        this.isNight = isNight;
    }
}
