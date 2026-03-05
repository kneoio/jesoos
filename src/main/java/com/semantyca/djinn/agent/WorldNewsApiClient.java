package com.semantyca.djinn.agent;

import com.semantyca.core.util.PropertiesUtil;
import com.semantyca.djinn.config.WorldNewsApiConfig;
import com.semantyca.djinn.service.live.scripting.NewsHelper;
import com.semantyca.mixpla.model.news.NewsResponse;
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
public class WorldNewsApiClient {

    @Inject
    WorldNewsApiConfig config;

    @Inject
    Vertx vertx;

    private WebClient webClient;

    @PostConstruct
    void init() {
        this.webClient = WebClient.create(vertx);
    }

    public Uni<JsonObject> searchNews(String text, String sourceCountry, String language, Integer number) {
        var request = webClient
                .getAbs(config.getBaseUrl() + "/search-news")
                .addQueryParam("api-key", config.getApiKey());
        
        if (text != null) request.addQueryParam("text", text);
        if (sourceCountry != null) request.addQueryParam("source-country", sourceCountry);
        if (language != null) request.addQueryParam("language", language);
        if (number != null) request.addQueryParam("number", String.valueOf(number));
        
        return request.send().map(HttpResponse::bodyAsJsonObject);
    }

    public static void main(String[] args) {
        String apiKey = PropertiesUtil.getDevProperty("worldnews.api.key");
        if (apiKey == null || apiKey.isEmpty()) {
            System.err.println("ERROR: worldnews.api.key not found");
            return;
        }
        
        io.vertx.core.Vertx coreVertx = io.vertx.core.Vertx.vertx();
        Vertx vertx = new Vertx(coreVertx);
        
        WorldNewsApiClient apiClient = new WorldNewsApiClient();
        apiClient.webClient = WebClient.create(vertx);

        apiClient.config = new WorldNewsApiConfig() {
            @Override
            public String getApiKey() { return apiKey; }
            @Override
            public String getBaseUrl() { return "https://api.worldnewsapi.com"; }
        };
        
        NewsHelper newsHelper = new NewsHelper(apiClient, CountryCode.KZ, "ru");
        
        try {
            System.out.println("\n=== Latest Music News (3 items) ===");
            NewsResponse response = newsHelper.search("world", 3);
            
            System.out.printf("Found %d of %d available articles\n\n", 
                response.getNews().size(), 
                response.getAvailable());
                
            for (int i = 0; i < response.getNews().size(); i++) {
                var article = response.getNews().get(i);
                System.out.printf("%d. %s%n", i + 1, article.getTitle());
                System.out.printf("   Summary: %s%n", 
                    article.getSummary() != null ? 
                        (article.getSummary().length() > 150 ? 
                            article.getSummary().substring(0, 150) + "..." : 
                            article.getSummary())
                        : "No summary available");
                
                if (article.getPublishDate() != null) {
                    System.out.printf("   Published: %s%n", 
                        article.getPublishDate().format(
                            java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME));
                }
                
                if (article.getAuthor() != null && !article.getAuthor().isEmpty()) {
                    System.out.printf("   Author: %s%n", article.getAuthor());
                }
                
                if (article.getUrl() != null) {
                    System.out.printf("   Read more: %s%n", article.getUrl());
                }
                
                System.out.println();
            }
        } catch (Exception e) {
            System.err.println("Error fetching news: " + e.getMessage());
            e.printStackTrace();
        }
        
        coreVertx.close();
    }
}
