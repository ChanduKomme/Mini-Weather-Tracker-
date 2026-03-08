package com.example.miniweathertracker;

import java.util.Random;

public class WeatherRepository {
    private static final String[] CONDITIONS = {"Sunny", "Cloudy", "Rain"};
    private static final Random R = new Random();

    public WeatherData getCurrentWeather() {
        int temp = 18 + R.nextInt(17); // 18..34
        int hum  = 35 + R.nextInt(55); // 35..89
        String cond = CONDITIONS[R.nextInt(CONDITIONS.length)];
        return new WeatherData(temp, hum, cond);
    }

}
