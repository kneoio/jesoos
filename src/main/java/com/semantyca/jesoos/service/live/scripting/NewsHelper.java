package com.semantyca.jesoos.service.live.scripting;

import com.semantyca.jesoos.external.WorldNewsApiClient;
import com.semantyca.jesoos.util.NewsMapper;
import com.semantyca.mixpla.model.news.NewsArticle;
import com.semantyca.mixpla.model.news.NewsResponse;
import com.semantyca.officeframe.model.cnst.CountryCode;
import io.vertx.core.json.JsonObject;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;


public final class NewsHelper {
    private final WorldNewsApiClient client;
    private final String defaultCountry;
    private final String defaultLanguage;

    public NewsHelper(WorldNewsApiClient client, CountryCode defaultCountry, String defaultLanguage) {
        this.client = client;
        this.defaultCountry = defaultCountry.getIsoCode();
        this.defaultLanguage = defaultLanguage;
    }

    public NewsResponse search(String text) {
        return doSearch(text, defaultCountry, defaultLanguage, 10);
    }

    public NewsResponse search(String text, int number) {
        return doSearch(text, defaultCountry, defaultLanguage, number);
    }

    public NewsResponse search(String text, String country, String language) {
        return doSearch(text, country, language, 10);
    }

    private NewsResponse doSearch(String text, String country, String language, int number) {
        JsonObject jsonResponse = client.searchNews(text, country, language, number)
                .await().atMost(Duration.ofSeconds(30));
        return NewsMapper.fromJson(jsonResponse);
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
