package com.semantyca.djinn.service.live.scripting;

import com.semantyca.djinn.agent.WeatherApiClient;
import io.kneo.officeframe.cnst.CountryCode;
import io.vertx.core.json.JsonObject;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class WeatherHelper {
    private final WeatherApiClient client;
    private final Map<String, CachedWeather> cache = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 10 * 60 * 1000;
    private CountryCode defaultCountry;

    public WeatherHelper(WeatherApiClient client, CountryCode defaultCountry) {
        this.client = client;
        this.defaultCountry = defaultCountry;
    }

    public WeatherHelper(WeatherApiClient client) {
        this.client = client;
    }

    public Map<String, Object> get(CountryCode country, String city) {
        return formatWeather(getWeatherDataBlocking(city, country));
    }

    public Map<String, Object> get(String city) {
        return get(defaultCountry, city);
    }

    public Map<String, Object> get() {
        String city = defaultCityFor(defaultCountry);
        if (city == null) {
            return new HashMap<>();
        }
        try {
            JsonObject data = client.getCurrentWeather(city, defaultCountry).await().indefinitely();
            return formatWeather(data);
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    public String summary(String city) {
        Map<String, Object> w = get(defaultCountry, city);
        return buildSummary(w);
    }

    public String summary(CountryCode country, String city) {
        Map<String, Object> w = get(country, city);
        return buildSummary(w);
    }

    public String summary() {
        Map<String, Object> w = get();
        if (w.isEmpty()) {
            return "";
        }
        return buildSummary(w);
    }

    private String buildSummary(Map<String, Object> w) {
        StringBuilder result = new StringBuilder();
        
        result.append(w.get("city")).append(": ");
        result.append(w.get("temp")).append("°C");
        
        if (w.containsKey("feelsLike")) {
            double temp = (double) w.get("temp");
            double feelsLike = (double) w.get("feelsLike");
            if (Math.abs(temp - feelsLike) > 3) {
                result.append(" (feels like ").append(feelsLike).append("°C)");
            }
        }
        
        result.append(", ").append(w.get("description"));
        
        if (w.containsKey("wind")) {
            result.append(", wind ").append(w.get("wind"));
        }
        
        if (w.containsKey("pressure")) {
            result.append(", pressure ").append(w.get("pressure")).append(" hPa");
        }
        
        if (w.containsKey("humidity")) {
            result.append(", humidity ").append(w.get("humidity")).append("%");
        }
        
        return result.toString();
    }

    private JsonObject getWeatherDataBlocking(String city, CountryCode countryCode) {
        String cacheKey = city + "," + countryCode;
        CachedWeather cached = cache.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            return cached.data;
        }
        
        try {
            JsonObject data = client.getCurrentWeather(city, countryCode)
                    .await().indefinitely();
            
            cache.put(cacheKey, new CachedWeather(data));
            return data;
        } catch (Exception e) {
            return new JsonObject();
        }
    }

    private Map<String, Object> formatWeather(JsonObject weather) {
        Map<String, Object> result = new HashMap<>();
        
        result.put("city", weather.getString("name"));
        
        JsonObject main = weather.getJsonObject("main");
        if (main != null) {
            double temp = main.getDouble("temp");
            double feelsLike = main.getDouble("feels_like");
            int pressure = main.getInteger("pressure");
            int humidity = main.getInteger("humidity");
            
            result.put("temp", temp);
            result.put("feelsLike", feelsLike);
            result.put("humidity", humidity);
            
            if (pressure < 1000) {
                result.put("pressure", pressure);
                result.put("pressureHint", "low pressure - stormy weather likely");
            } else if (pressure > 1020) {
                result.put("pressure", pressure);
                result.put("pressureHint", "high pressure - stable, clear weather");
            } else if (Math.abs(pressure - 1013) > 10) {
                result.put("pressure", pressure);
                result.put("pressureHint", "pressure changing - weather may shift");
            }
            
            double tempDiff = Math.abs(temp - feelsLike);
            if (tempDiff > 3) {
                result.put("feelsLikeHint", "feels " + (feelsLike < temp ? "colder" : "warmer") + " than actual temperature");
            }
        }
        
        JsonObject wind = weather.getJsonObject("wind");
        if (wind != null) {
            double windSpeed = wind.getDouble("speed");
            
            if (windSpeed > 5.0) {
                String windInfo = windSpeed + " m/s";
                if (wind.containsKey("deg")) {
                    int windDeg = wind.getInteger("deg");
                    windInfo += " from " + getWindDirection(windDeg);
                }
                result.put("wind", windInfo);
                
                if (windSpeed > 10.0) {
                    result.put("windHint", "strong winds - worth mentioning for outdoor activities");
                } else {
                    result.put("windHint", "moderate winds - noticeable breeze");
                }
            } else if (windSpeed < 2.0) {
                result.put("windHint", "calm conditions");
            }
        }
        
        if (weather.containsKey("weather") && !weather.getJsonArray("weather").isEmpty()) {
            JsonObject weatherDesc = weather.getJsonArray("weather").getJsonObject(0);
            result.put("description", weatherDesc.getString("description"));
            result.put("main", weatherDesc.getString("main"));
        }
        
        if (weather.containsKey("visibility")) {
            int visibility = weather.getInteger("visibility");
            result.put("visibility", visibility);
            if (visibility < 1000) {
                result.put("visibilityHint", "poor visibility - fog or heavy precipitation");
            }
        }
        
        return result;
    }

    private String getWindDirection(int degrees) {
        String[] directions = {"N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", 
                               "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"};
        int index = (int) Math.round(((degrees % 360) / 22.5));
        return directions[index % 16];
    }

    private String defaultCityFor(CountryCode countryCode) {
        if (countryCode == null) return null;
        return switch (countryCode) {
            case US -> "Washington";
            case GB -> "London";
            case FR -> "Paris";
            case DE -> "Berlin";
            case ES -> "Madrid";
            case IT -> "Rome";
            case PT -> "Lisbon";
            case BR -> "Brasilia";
            case CA -> "Ottawa";
            case AU -> "Canberra";
            case IN -> "New Delhi";
            case JP -> "Tokyo";
            case CN -> "Beijing";
            case RU -> "Moscow";
            case UA -> "Kyiv";
            case PL -> "Warsaw";
            case NL -> "Amsterdam";
            case BE -> "Brussels";
            case SE -> "Stockholm";
            case NO -> "Oslo";
            case DK -> "Copenhagen";
            case FI -> "Helsinki";
            case IE -> "Dublin";
            case CH -> "Bern";
            default -> null;
        };
    }

    private static class CachedWeather {
        final JsonObject data;
        final long timestamp;

        CachedWeather(JsonObject data) {
            this.data = data;
            this.timestamp = System.currentTimeMillis();
        }

        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > CACHE_TTL_MS;
        }
    }
}
