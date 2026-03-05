package com.semantyca.djinn.service.live.scripting;

import com.semantyca.djinn.agent.WorldNewsApiClient;
import com.semantyca.djinn.util.NewsMapper;
import com.semantyca.mixpla.model.news.NewsArticle;
import com.semantyca.mixpla.model.news.NewsResponse;
import io.kneo.officeframe.cnst.CountryCode;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;


public final class NewsHelper {
    private final WorldNewsApiClient client;
    private final String defaultCountry;
    private final String defaultLanguage;
    private final Map<String, CachedNews> cache = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 15 * 60 * 1000; // 15 minutes
    
    private static class CachedNews {
        final NewsResponse data;
        final long timestamp;

        CachedNews(NewsResponse data) {
            this.data = data;
            this.timestamp = System.currentTimeMillis();
        }

        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > CACHE_TTL_MS;
        }
    }

    public NewsHelper(WorldNewsApiClient client, CountryCode defaultCountry, String defaultLanguage) {
        this.client = client;
        this.defaultCountry = defaultCountry.getIsoCode();
        this.defaultLanguage = defaultLanguage;
    }
    
    public NewsResponse search(String text) {
        return searchWithCache(text, defaultCountry, defaultLanguage, 10);
    }

    public NewsResponse search(String text, int number) {
        return searchWithCache(text, defaultCountry, defaultLanguage, number);
    }

    public NewsResponse search(String text, String country, String language) {
        return searchWithCache(text, country, language, 10);
    }

    private NewsResponse searchWithCache(String text, String country, String language, int number) {
        String cacheKey = text + "|" + country + "|" + language + "|" + number;
        
        CachedNews cached = cache.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            return cached.data;
        }
        
        try {
            JsonObject jsonResponse = client.searchNews(text, country, language, number)
                    .await().indefinitely();
            
            NewsResponse response = NewsMapper.fromJson(jsonResponse);
            cache.put(cacheKey, new CachedNews(response));
            return response;
        } catch (Exception e) {
            NewsResponse errorResponse = new NewsResponse();
            errorResponse.setNews(List.of());
            return errorResponse;
        }
    }

    public List<String> headlines(String text, int number) {
        NewsResponse response = search(text, number);
        return response.getNews().stream()
                .map(NewsArticle::getTitle)
                .collect(Collectors.toList());
    }

    public List<String> headlines(String text, int number, String country, String language) {
        NewsResponse response = search(text, country, language);
        return response.getNews().stream()
                .limit(number)
                .map(NewsArticle::getTitle)
                .collect(Collectors.toList());
    }

    public List<String> summaries(String text, int number) {
        return search(text, number).getNews().stream()
                .map(NewsArticle::getSummary)
                .filter(summary -> summary != null && !summary.isEmpty())
                .collect(Collectors.toList());
    }

    public String brief(String text) {
        return search(text, 1).getNews().stream()
                .findFirst()
                .map(NewsArticle::getSummary)
                .orElse("No news found");
    }

    public List<JsonObject> getSimplifiedArticles(String text, int number) {
        NewsResponse response = search(text, number);
        List<JsonObject> articles = new ArrayList<>();
        
        for (NewsArticle article : response.getNews()) {
            JsonObject simplified = new JsonObject()
                .put("title", article.getTitle())
                .put("summary", article.getSummary())
                .put("url", article.getUrl())
                .put("author", article.getAuthor())
                .put("date", article.getPublishDate() != null ? article.getPublishDate().toString() : null)
                .put("sentiment", 0.0); // Default sentiment, can be updated if needed
            articles.add(simplified);
        }
        
        return articles;
    }

}
