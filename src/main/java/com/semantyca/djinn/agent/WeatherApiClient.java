package com.semantyca.djinn.agent;

import com.semantyca.core.util.PropertiesUtil;
import com.semantyca.djinn.config.WeatherApiConfig;
import com.semantyca.djinn.service.live.scripting.WeatherHelper;
import io.kneo.officeframe.cnst.CountryCode;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.ext.web.client.HttpResponse;
import io.vertx.mutiny.ext.web.client.WebClient;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class WeatherApiClient {

    @Inject
    WeatherApiConfig config;

    @Inject
    Vertx vertx;

    private WebClient webClient;

    @PostConstruct
    void init() {
        this.webClient = WebClient.create(vertx);
    }

    public Uni<JsonObject> getCurrentWeather(String city, CountryCode countryCode) {
        String location = countryCode != null ? city + "," + countryCode : city;
        return webClient
                .getAbs(config.getBaseUrl() + "/weather")
                .addQueryParam("q", location)
                .addQueryParam("appid", config.getApiKey())
                .addQueryParam("units", "metric")
                .send()
                .map(HttpResponse::bodyAsJsonObject);
    }

    public static void main(String[] args) {
        String apiKey = PropertiesUtil.getDevProperty("weather.api.key");
        if (apiKey == null || apiKey.isEmpty()) {
            System.err.println("ERROR: weather.api.key not found");
            return;
        }
        
        io.vertx.core.Vertx coreVertx = io.vertx.core.Vertx.vertx();
        Vertx vertx = new Vertx(coreVertx);
        
        WeatherApiClient apiClient = new WeatherApiClient();
        apiClient.webClient = WebClient.create(vertx);
        
        apiClient.config = new WeatherApiConfig() {
            @Override
            public String getApiKey() { return apiKey; }
            @Override
            public String getBaseUrl() { return "https://api.openweathermap.org/data/2.5"; }
        };
        
        WeatherHelper weatherHelper = new WeatherHelper(apiClient);
        
        try {
            String weather = weatherHelper.summary(CountryCode.PT,"Leiria");
            System.out.println(weather);
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
      
        coreVertx.close();
    }
}
