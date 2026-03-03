package com.semantyca.djinn.llm;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@ApplicationScoped
public class LlmClientImpl implements LlmClient {
    
    private static final Logger LOGGER = Logger.getLogger(LlmClientImpl.class);
    
    @ConfigProperty(name = "llm.groq.api-key")
    String groqApiKey;
    
    @ConfigProperty(name = "llm.openai.api-key")
    String openaiApiKey;
    
    @ConfigProperty(name = "llm.anthropic.api-key")
    String anthropicApiKey;
    
    @ConfigProperty(name = "llm.groq.model", defaultValue = "llama3-70b-8192")
    String groqModel;
    
    @ConfigProperty(name = "llm.openai.model", defaultValue = "gpt-4")
    String openaiModel;
    
    @ConfigProperty(name = "llm.anthropic.model", defaultValue = "claude-3-sonnet-20240229")
    String anthropicModel;
    
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    
    public LlmClientImpl() {
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
        this.objectMapper = new ObjectMapper();
    }
    
    @Override
    public Uni<String> invoke(String prompt, String llmType) {
        return switch (llmType.toUpperCase()) {
            case "GROQ" -> invokeGroq(prompt);
            case "OPENAI" -> invokeOpenAI(prompt);
            case "ANTHROPIC" -> invokeAnthropic(prompt);
            default -> Uni.createFrom().failure(
                new IllegalArgumentException("Unknown LLM type: " + llmType)
            );
        };
    }
    
    private Uni<String> invokeGroq(String prompt) {
        return Uni.createFrom().completionStage(() -> {
            try {
                String requestBody = String.format("""
                    {
                        "model": "%s",
                        "messages": [
                            {
                                "role": "system",
                                "content": "You are a professional radio DJ. CRITICAL: Use ONLY song information from 'Draft input:'. NEVER use song names from past context."
                            },
                            {
                                "role": "user",
                                "content": "%s"
                            }
                        ],
                        "temperature": 0.7,
                        "max_tokens": 500
                    }
                    """, groqModel, prompt.replace("\"", "\\\""));
                
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.groq.com/openai/v1/chat/completions"))
                    .header("Authorization", "Bearer " + groqApiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();
                
                return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply(response -> {
                        if (response.statusCode() == 200) {
                            try {
                                JsonNode root = objectMapper.readTree(response.body());
                                return root.path("choices").get(0).path("message").path("content").asText();
                            } catch (IOException e) {
                                LOGGER.error("Failed to parse Groq response", e);
                                throw new RuntimeException("Failed to parse Groq response", e);
                            }
                        } else {
                            LOGGER.error("Groq API error: " + response.statusCode() + " " + response.body());
                            throw new RuntimeException("Groq API error: " + response.statusCode());
                        }
                    });
            } catch (Exception e) {
                LOGGER.error("Failed to call Groq API", e);
                throw new RuntimeException("Failed to call Groq API", e);
            }
        });
    }
    
    private Uni<String> invokeOpenAI(String prompt) {
        return Uni.createFrom().completionStage(() -> {
            try {
                String requestBody = String.format("""
                    {
                        "model": "%s",
                        "messages": [
                            {
                                "role": "system",
                                "content": "You are a professional radio DJ. CRITICAL: Use ONLY song information from 'Draft input:'. NEVER use song names from past context."
                            },
                            {
                                "role": "user",
                                "content": "%s"
                            }
                        ],
                        "temperature": 0.7,
                        "max_tokens": 500
                    }
                    """, openaiModel, prompt.replace("\"", "\\\""));
                
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.openai.com/v1/chat/completions"))
                    .header("Authorization", "Bearer " + openaiApiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();
                
                return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply(response -> {
                        if (response.statusCode() == 200) {
                            try {
                                JsonNode root = objectMapper.readTree(response.body());
                                return root.path("choices").get(0).path("message").path("content").asText();
                            } catch (IOException e) {
                                LOGGER.error("Failed to parse OpenAI response", e);
                                throw new RuntimeException("Failed to parse OpenAI response", e);
                            }
                        } else {
                            LOGGER.error("OpenAI API error: " + response.statusCode() + " " + response.body());
                            throw new RuntimeException("OpenAI API error: " + response.statusCode());
                        }
                    });
            } catch (Exception e) {
                LOGGER.error("Failed to call OpenAI API", e);
                throw new RuntimeException("Failed to call OpenAI API", e);
            }
        });
    }
    
    private Uni<String> invokeAnthropic(String prompt) {
        return Uni.createFrom().completionStage(() -> {
            try {
                String requestBody = String.format("""
                    {
                        "model": "%s",
                        "max_tokens": 500,
                        "messages": [
                            {
                                "role": "user",
                                "content": "You are a professional radio DJ. CRITICAL: Use ONLY song information from 'Draft input:'. NEVER use song names from past context.\\n\\n%s"
                            }
                        ],
                        "temperature": 0.7
                    }
                    """, anthropicModel, prompt.replace("\"", "\\\""));
                
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.anthropic.com/v1/messages"))
                    .header("x-api-key", anthropicApiKey)
                    .header("Content-Type", "application/json")
                    .header("anthropic-version", "2023-06-01")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();
                
                return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply(response -> {
                        if (response.statusCode() == 200) {
                            try {
                                JsonNode root = objectMapper.readTree(response.body());
                                return root.path("content").get(0).path("text").asText();
                            } catch (IOException e) {
                                LOGGER.error("Failed to parse Anthropic response", e);
                                throw new RuntimeException("Failed to parse Anthropic response", e);
                            }
                        } else {
                            LOGGER.error("Anthropic API error: " + response.statusCode() + " " + response.body());
                            throw new RuntimeException("Anthropic API error: " + response.statusCode());
                        }
                    });
            } catch (Exception e) {
                LOGGER.error("Failed to call Anthropic API", e);
                throw new RuntimeException("Failed to call Anthropic API", e);
            }
        });
    }
}
