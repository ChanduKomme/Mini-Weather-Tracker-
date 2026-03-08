package com.example.miniweathertracker;

public class WeatherData {
    public final int tempC;
    public final int humidity;
    public final String condition; // "Sunny", "Cloudy", "Rain"

    public WeatherData(int tempC, int humidity, String condition) {
        this.tempC = tempC;
        this.humidity = humidity;
        this.condition = condition;
    }
}
