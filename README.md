# Mini Weather Tracker

An Android weather app built with **Java** and **Android Views**. It displays current conditions, hourly and seven-day forecasts, and wind direction for your device location or a saved place. Weather data is requested from [Open-Meteo](https://open-meteo.com/).

> **Data accuracy:** The app uses live API responses when available, but some screens can display **generated fallback values** if location access or a weather request fails. Treat fallback readings as demonstration data, not measured weather.

## Features

- **Current weather:** temperature, humidity, conditions, and additional details such as apparent temperature and daily minimum/maximum.
- **Hourly forecast:** hourly conditions and precipitation information.
- **Forecast graph:** a seven-day view of temperatures and precipitation probability.
- **Wind compass:** wind speed/direction from the forecast API, alongside a device-sensor heading when supported.
- **Saved places:** search for locations, save or remove them, and choose a default place. The app can also use the device's current location.
- **Bottom navigation:** switch between the current, graph, hourly, compass, and saved-places screens.

## Requirements

- **Android Studio** with the Android SDK installed.
- **Android SDK 35** to compile the project (`compileSdk = 35`).
- **JDK 17** to run the Android Gradle Plugin; the app's Java source/target compatibility is set to Java 11.
- An Android device or emulator running **Android 11 / API 30 or newer** (`minSdk = 30`).
- Internet access for live forecasts and location search; location permission for device-based weather.

The repository uses **Android Gradle Plugin 8.9.0** and the included **Gradle 8.11.1 wrapper**. No API key is configured in the project for its Open-Meteo requests.

## Get started

1. Clone the repository:

   ```bash
   git clone https://github.com/ChanduKomme/Mini-Weather-Tracker-.git
   cd Mini-Weather-Tracker-
   ```

2. Open the **repository root** in Android Studio (the folder containing `settings.gradle.kts`).
3. Allow Gradle to sync and install the requested Android SDK components.
4. Choose an Android 11+ emulator or connected device and run the **app** configuration.
5. Grant location access when prompted to view weather for your device location, or use **Home / Saved places** to search for a city and set it as the default.

### Build from a terminal

From the repository root, with the Android SDK and JDK configured:

```bash
./gradlew :app:assembleDebug
```

On Windows, use `gradlew.bat :app:assembleDebug`. A successful build produces a debug APK under `app/build/outputs/apk/debug/`. To install on a connected device with USB debugging enabled, run `./gradlew :app:installDebug`.

*These are the project's configured Gradle commands; a build was not executed as part of this documentation update.*

## Using the app

1. **Current:** the launcher opens the current-weather screen. With no saved default, it requests your device's location.
2. **Home:** search for a city using the location suggestions. Tap a result to save it; tap a saved place to view its weather.
3. **Default location:** use the saved-place control to set or clear a default. Other weather screens can use that location instead of your device position.
4. **Graph / Hourly:** view the forecast for the selected or default location.
5. **Compass:** view wind information; the heading indicator depends on the device's available motion/magnetic sensors.

## Data sources and permissions

| Source or storage | Used for |
| --- | --- |
| [Open-Meteo Forecast API](https://open-meteo.com/en/docs) | Current weather, hourly forecasts, daily forecasts, and wind |
| [Open-Meteo Geocoding API](https://open-meteo.com/en/docs/geocoding-api) | City/place search suggestions |
| [Open-Meteo Air Quality API](https://open-meteo.com/en/docs/air-quality-api) | Air-quality information on the current-weather screen |
| Android location services | Device coordinates when a default/specific place is not selected |
| Android `SharedPreferences` | Locally saved places and default-location coordinates |
| Android device sensors | Compass heading and light-related display behavior where available |

The manifest requests `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_COARSE_LOCATION`, and `ACCESS_FINE_LOCATION`. Weather and geocoding requests send the relevant location coordinates or search text to third-party Open-Meteo services. Saved places are stored locally on the device. Review these behaviors before using the app with sensitive location data.

## Limitations and troubleshooting

- **Unexpected temperatures or conditions?** The current-weather screen uses `WeatherRepository` to generate sample values when it cannot obtain usable location/weather data. The hourly screen also has a mock fallback. Check connectivity and location permissions before relying on a reading.
- **No location shown?** Enable device location services, grant the requested permission, or select a saved place and make it the default.
- **Compass heading not updating?** The screen uses a rotation-vector sensor or accelerometer/magnetometer fallback; some emulators and devices lack the required sensors.
- **Build or sync failure?** Check that Android SDK 35 is installed, Android Studio uses JDK 17, and Gradle can download dependencies.
- **Testing status:** the repository includes example unit/instrumentation test files, but their presence does not establish comprehensive test coverage.

## Code map

| Path | Responsibility |
| --- | --- |
| [`app/src/main/java/com/example/miniweathertracker/CurrentWeatherActivity.java`](app/src/main/java/com/example/miniweathertracker/CurrentWeatherActivity.java) | Current conditions, device location, online fetch and fallback |
| [`HourlyForecastActivity.java`](app/src/main/java/com/example/miniweathertracker/HourlyForecastActivity.java) | Hourly forecast |
| [`ForecastGraphActivity.java`](app/src/main/java/com/example/miniweathertracker/ForecastGraphActivity.java) | Seven-day graph |
| [`WindCompassActivity.java`](app/src/main/java/com/example/miniweathertracker/WindCompassActivity.java) | Wind and device heading |
| [`HomeLocationsActivity.java`](app/src/main/java/com/example/miniweathertracker/HomeLocationsActivity.java) | Search and saved-place UI |
| [`PlaceStore.java`](app/src/main/java/com/example/miniweathertracker/PlaceStore.java) | Local saved-place storage |
| [`WeatherRepository.java`](app/src/main/java/com/example/miniweathertracker/WeatherRepository.java) | Generated fallback weather values |
| [`app/build.gradle.kts`](app/build.gradle.kts) | Android app build settings |
