package com.semantyca.djinn.util;

import com.semantyca.mixpla.model.news.NewsArticle;
import com.semantyca.mixpla.model.news.NewsResponse;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class NewsMapper {

    public static NewsResponse fromJson(JsonObject json) {
        if (json == null) {
            return null;
        }
        
        NewsResponse response = new NewsResponse();
        response.setOffset(json.getInteger("offset", 0));
        response.setNumber(json.getInteger("number", 0));
        response.setAvailable(json.getInteger("available", 0));
        
        List<NewsArticle> articles = new ArrayList<>();
        JsonArray newsArray = json.getJsonArray("news");
        if (newsArray != null) {
            for (int i = 0; i < newsArray.size(); i++) {
                JsonObject articleJson = newsArray.getJsonObject(i);
                articles.add(mapArticle(articleJson));
            }
        }
        response.setNews(articles);
        
        return response;
    }

    private static final DateTimeFormatter[] DATE_FORMATTERS = {
        DateTimeFormatter.ISO_OFFSET_DATE_TIME,  // ISO format with timezone: 2025-11-07T18:30:00+00:00
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),  // Format from logs: 2025-10-09 07:44:33
        DateTimeFormatter.ISO_LOCAL_DATE_TIME   // Fallback: ISO format without timezone
    };

    private static NewsArticle mapArticle(JsonObject json) {
        if (json == null) {
            return null;
        }
        
        NewsArticle article = new NewsArticle();
        article.setId(json.getInteger("id"));
        article.setTitle(json.getString("title"));
        article.setText(json.getString("text"));
        article.setSummary(json.getString("summary"));
        article.setUrl(json.getString("url"));
        article.setImage(json.getString("image"));
        article.setVideo(json.getString("video"));
        
        String dateStr = json.getString("publish_date");
        if (dateStr != null && !dateStr.isEmpty()) {
            boolean dateParsed = false;
            
            // Try each formatter until one works
            for (DateTimeFormatter formatter : DATE_FORMATTERS) {
                try {
                    // For formats without timezone, parse as local date time and convert to OffsetDateTime
                    if (formatter == DATE_FORMATTERS[1] || formatter == DATE_FORMATTERS[2]) {
                        article.setPublishDate(
                            java.time.LocalDateTime.parse(dateStr, formatter)
                                .atZone(java.time.ZoneId.systemDefault())
                                .toOffsetDateTime()
                        );
                    } else {
                        article.setPublishDate(OffsetDateTime.parse(dateStr, formatter));
                    }
                    dateParsed = true;
                    break;
                } catch (Exception e) {
                    // Try next formatter
                    continue;
                }
            }
            
            if (!dateParsed) {
                System.err.println("Failed to parse date with any formatter: " + dateStr);
            }
        }
        
        article.setAuthor(json.getString("author"));
        
        JsonArray authorsArray = json.getJsonArray("authors");
        if (authorsArray != null) {
            List<String> authors = new ArrayList<>();
            for (int i = 0; i < authorsArray.size(); i++) {
                authors.add(authorsArray.getString(i));
            }
            article.setAuthors(authors);
        }
        
        article.setLanguage(json.getString("language"));
        article.setCategory(json.getString("category"));
        article.setSourceCountry(json.getString("source_country"));
        
        return article;
    }
}
