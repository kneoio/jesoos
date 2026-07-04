package com.semantyca.jesoos.service.chat.ad;

import com.semantyca.core.model.user.AnonymousUser;
import com.semantyca.jesoos.config.JesoosConfig;
import com.semantyca.jesoos.repository.UserAdRepository;
import com.semantyca.jesoos.service.chat.llm.BrandLlmProviderResolver;
import com.semantyca.jesoos.service.chat.llm.LlmMessage;
import com.semantyca.jesoos.service.chat.llm.LlmRequest;
import com.semantyca.jesoos.service.chat.llm.LlmResponse;
import com.semantyca.jesoos.service.chat.llm.LlmUseCase;
import com.semantyca.mixpla.model.UserAd;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.StateGraph;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;

@ApplicationScoped
public class AdGraph {

    private static final Logger LOGGER = Logger.getLogger(AdGraph.class);

    private static final List<String> CLASSIFIED_REQUIRED_VARS = List.of("description", "details", "contacts");
    private static final List<String> STORE_PROMO_REQUIRED_VARS = List.of("description", "validity");
    private static final Map<String, String> VAR_DESCRIPTIONS = Map.of(
            "description", "the ad text or message you want to broadcast",
            "details", "optional extra details like price, year, location, condition",
            "contacts", "contact information (phone, website, email, etc.)",
            "validity", "how long the promotion runs"
    );
    private static final List<String> USER_DATA_FIELDS = List.of(
            "category", "price", "location", "brand", "year", "condition", "mileage"
    );
    private static final Map<String, List<String>> CATEGORY_DETAILS = Map.of(
            "car",         List.of("price", "year", "mileage", "condition", "location"),
            "property",    List.of("price", "location", "condition"),
            "electronics", List.of("brand", "price", "condition", "year"),
            "service",     List.of("location", "price"),
            "job",         List.of("location", "price"),
            "pet",         List.of("price", "location", "condition")
    );

    @Inject
    AdSessionManager sessionManager;

    @Inject
    UserAdRepository userAdRepository;

    @Inject
    JesoosConfig config;

    @Inject
    BrandLlmProviderResolver llmProviderResolver;

    private CompiledGraph<AdState> compiledGraph;

    @PostConstruct
    void init() {
        try {
            compiledGraph = new StateGraph<>(AdState::new)
                    .addNode("classifyAdType", this::classifyAdTypeNode)
                    .addNode("collectTurn",    this::collectTurnNode)
                    .addNode("checkMissing",   this::checkMissingNode)
                    .addNode("askQuestion",    this::askQuestionNode)
                    .addNode("saveAndGenerate", this::saveAndGenerateNode)
                    .addEdge(START, "classifyAdType")
                    .addEdge("classifyAdType", "collectTurn")
                    .addEdge("collectTurn", "checkMissing")
                    .addConditionalEdges("checkMissing",
                            state -> CompletableFuture.completedFuture(
                                    "save".equals(state.action()) ? "saveAndGenerate" : "askQuestion"),
                            Map.of("askQuestion", "askQuestion", "saveAndGenerate", "saveAndGenerate"))
                    .addEdge("askQuestion", END)
                    .addEdge("saveAndGenerate", END)
                    .compile();

            LOGGER.info("[AdGraph] compiled successfully");
        } catch (Exception e) {
            throw new RuntimeException("Failed to compile AdGraph", e);
        }
    }

    private static List<String> requiredVarsFor(String adType) {
        return "STORE_PROMO".equals(adType) ? STORE_PROMO_REQUIRED_VARS : CLASSIFIED_REQUIRED_VARS;
    }

    private CompletableFuture<Map<String, Object>> classifyAdTypeNode(AdState state) {
        if (state.adType() != null || state.userMessage().isBlank()) {
            return CompletableFuture.completedFuture(Map.of());
        }
        return Uni.createFrom().item(() -> {
            LlmRequest request = LlmRequest.builder()
                    .model(llmProviderResolver.modelFor(state.brandSlug(), LlmUseCase.FOLLOW_UP))
                    .maxTokens(20)
                    .system("""
                            Classify this radio ad request as either:
                            - CLASSIFIED: a personal item, vehicle, property, service, or job being sold/offered by an individual
                            - STORE_PROMO: a store/business promotion, discount, sale, or special offer

                            Reply with ONLY the single word CLASSIFIED or STORE_PROMO.""")
                    .addMessage(LlmMessage.text(LlmMessage.Role.USER, state.userMessage()))
                    .build();
            LlmResponse response = llmProviderResolver.clientFor(state.brandSlug())
                    .createMessage(request).toCompletableFuture().join();
            String raw = response.text() != null ? response.text().trim().toUpperCase() : "CLASSIFIED";
            String adType = raw.contains("STORE_PROMO") ? "STORE_PROMO" : "CLASSIFIED";
            LOGGER.infof("[AdGraph] classified adType=%s", adType);
            Map<String, Object> result = new HashMap<>();
            result.put(AdState.AD_TYPE, adType);
            result.put(AdState.REQUIRED_VARS, requiredVarsFor(adType));
            return result;
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .subscribeAsCompletionStage();
    }

    private CompletableFuture<Map<String, Object>> collectTurnNode(AdState state) {
        if (state.userMessage().isBlank()) {
            return CompletableFuture.completedFuture(Map.of());
        }
        if ("STORE_PROMO".equals(state.adType())) {
            return collectStorePromoTurnNode(state);
        }
        return Uni.createFrom().item(() -> {
            Map<String, String> current = new HashMap<>(state.collectedVars());
            String pending = state.pendingVar();
            String alreadyCollected = current.isEmpty() ? "none" : current.entrySet().stream()
                    .map(e -> e.getKey() + ": " + e.getValue())
                    .reduce((a, b) -> a + "; " + b).orElse("none");

            LlmRequest request = LlmRequest.builder()
                    .model(llmProviderResolver.modelFor(state.brandSlug(), LlmUseCase.FOLLOW_UP))
                    .maxTokens(300)
                    .system("""
                            You are extracting structured fields for a radio advertisement.
                            Already collected: """ + alreadyCollected + (pending != null && !pending.isBlank() ? "\nThe user was asked for: " + pending : "") + """

                            Extract from the user message and return ONLY a JSON object with these fields:
                            - description: full ad text — what is being sold/offered, model, year, condition, location, price, features, etc. Do NOT include contact info here.
                            - contacts: ALL contact info found — phone numbers, email, website. Keep numbers exactly as-is.
                            - category: one word category (e.g. car, property, electronics, service, job, pet, other)
                            - price: price as string if mentioned (e.g. "60000 EUR"), else ""
                            - location: city or region if mentioned, else ""
                            - brand: brand or make if mentioned (e.g. Mercedes, Apple, Fiat), else ""
                            - year: year if mentioned (e.g. "2015"), else ""
                            - condition: condition if mentioned (e.g. excellent, good, used, new), else ""
                            - mileage: mileage if mentioned (e.g. "25000 km"), else ""

                            Rules:
                            - Never put "yes", "no", "ok" as a field value — those are confirmations, not data
                            - If a field is not present, use ""
                            - Do not repeat already-collected fields unless the user is providing a correction
                            Return ONLY the JSON, no markdown.""")
                    .addMessage(LlmMessage.text(LlmMessage.Role.USER, state.userMessage()))
                    .build();

            LlmResponse response = llmProviderResolver.clientFor(state.brandSlug())
                    .createMessage(request).toCompletableFuture().join();
            String raw = response.text() != null ? response.text().trim() : "{}";
            if (raw.startsWith("```")) {
                raw = raw.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "").trim();
            }

            try {
                @SuppressWarnings("unchecked")
                Map<String, String> extracted = new com.fasterxml.jackson.databind.ObjectMapper().readValue(raw, Map.class);
                for (String field : CLASSIFIED_REQUIRED_VARS) {
                    String val = extracted.getOrDefault(field, "");
                    if (val != null && !val.isBlank()) {
                        current.put(field, val);
                    }
                }
                Map<String, String> userDataMap = new HashMap<>(state.userData());
                for (String field : USER_DATA_FIELDS) {
                    String val = extracted.getOrDefault(field, "");
                    if (val != null && !val.isBlank()) {
                        userDataMap.put(field, val);
                    }
                }
                if ("details".equals(pending)) {
                    current.put("details", "provided");
                }
                LOGGER.infof("[AdGraph] extracted fields=%s userData=%s", current.keySet(), userDataMap.keySet());
                Map<String, Object> result = new HashMap<>();
                result.put(AdState.COLLECTED_VARS, current);
                result.put(AdState.USER_DATA, userDataMap);
                return result;
            } catch (Exception e) {
                LOGGER.warnf("[AdGraph] extraction failed: %s raw=%s", e.getMessage(), raw);
            }
            return Map.<String, Object>of(AdState.COLLECTED_VARS, current);
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .subscribeAsCompletionStage();
    }

    private CompletableFuture<Map<String, Object>> collectStorePromoTurnNode(AdState state) {
        Map<String, String> current = new HashMap<>(state.collectedVars());
        String pending = state.pendingVar();
        String alreadyCollected = current.isEmpty() ? "none" : current.entrySet().stream()
                .map(e -> e.getKey() + ": " + e.getValue())
                .reduce((a, b) -> a + "; " + b).orElse("none");

        return Uni.createFrom().item(() -> {
            LlmRequest request = LlmRequest.builder()
                    .model(llmProviderResolver.modelFor(state.brandSlug(), LlmUseCase.FOLLOW_UP))
                    .maxTokens(200)
                    .system("""
                            You are extracting structured fields for a radio store-promotion announcement.
                            Already collected: """ + alreadyCollected + (pending != null && !pending.isBlank() ? "\nThe user was asked for: " + pending : "") + """

                            Extract from the user message and return ONLY a JSON object with these fields:
                            - description: what is being promoted — the discount, sale, or offer, and what it applies to
                            - validity: how long the promotion runs (e.g. "this week", "until March 15", "weekends only"), else ""

                            Rules:
                            - Never put "yes", "no", "ok" as a field value — those are confirmations, not data
                            - If a field is not present, use ""
                            - Do not repeat already-collected fields unless the user is providing a correction
                            Return ONLY the JSON, no markdown.""")
                    .addMessage(LlmMessage.text(LlmMessage.Role.USER, state.userMessage()))
                    .build();

            LlmResponse response = llmProviderResolver.clientFor(state.brandSlug())
                    .createMessage(request).toCompletableFuture().join();
            String raw = response.text() != null ? response.text().trim() : "{}";
            if (raw.startsWith("```")) {
                raw = raw.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "").trim();
            }

            try {
                @SuppressWarnings("unchecked")
                Map<String, String> extracted = new com.fasterxml.jackson.databind.ObjectMapper().readValue(raw, Map.class);
                for (String field : STORE_PROMO_REQUIRED_VARS) {
                    String val = extracted.getOrDefault(field, "");
                    if (val != null && !val.isBlank()) {
                        current.put(field, val);
                    }
                }
                LOGGER.infof("[AdGraph] extracted store-promo fields=%s", current.keySet());
                return Map.<String, Object>of(AdState.COLLECTED_VARS, current);
            } catch (Exception e) {
                LOGGER.warnf("[AdGraph] store-promo extraction failed: %s raw=%s", e.getMessage(), raw);
            }
            return Map.<String, Object>of(AdState.COLLECTED_VARS, current);
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .subscribeAsCompletionStage();
    }

    private CompletableFuture<Map<String, Object>> checkMissingNode(AdState state) {
        String nextMissing = state.requiredVars().stream()
                .filter(v -> !state.collectedVars().containsKey(v) || state.collectedVars().get(v).isBlank())
                .findFirst()
                .orElse(null);

        if (nextMissing == null) {
            LOGGER.info("[AdGraph] all vars collected → saveAndGenerate");
            return CompletableFuture.completedFuture(Map.of(AdState.ACTION, "save"));
        }
        LOGGER.infof("[AdGraph] next missing var=%s", nextMissing);
        return CompletableFuture.completedFuture(Map.of(
                AdState.ACTION, "ask",
                AdState.PENDING_VAR, nextMissing
        ));
    }

    private CompletableFuture<Map<String, Object>> askQuestionNode(AdState state) {
        String varName = state.pendingVar();
        String question = switch (varName) {
            case "description" -> "What should the ad say?";
            case "details" -> buildDetailsQuestion(state);
            case "contacts" -> "What contact info should listeners use?";
            case "validity" -> "How long does this promotion run?";
            default -> "What is the " + varName + "?";
        };
        LOGGER.infof("[AdGraph] asking for var=%s", varName);
        return CompletableFuture.completedFuture(Map.of(AdState.NEXT_QUESTION, question));
    }

    private CompletableFuture<Map<String, Object>> saveAndGenerateNode(AdState state) {
        String adId = state.savedAdId();
        return CompletableFuture.completedFuture(Map.of(
                AdState.SAVED_AD_ID, adId != null ? adId : ""
        ));
    }

    public Uni<AdResult> processUserTurn(String connectionId, String userMessage) {
        AdSessionData session = sessionManager.get(connectionId)
                .orElseThrow(() -> new IllegalStateException("No active Ad session for " + connectionId));

        Map<String, Object> initData = buildStateMap(session, userMessage);

        return Uni.createFrom().item(() -> {
            try {
                return compiledGraph.invoke(initData)
                        .orElseThrow(() -> new RuntimeException("AdGraph returned empty state"));
            } catch (Exception e) {
                throw new RuntimeException("AdGraph execution failed", e);
            }
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .flatMap(finalState -> {
                    if ("save".equals(finalState.action())) {
                        return saveAd(session, finalState)
                                .map(adId -> {
                                    sessionManager.end(connectionId);
                                    return new AdResult(AdResult.Action.AD_CREATED, adId, null);
                                });
                    } else {
                        session.setCollectedVars(finalState.collectedVars());
                        session.setUserData(finalState.userData());
                        session.setPendingVar(finalState.pendingVar());
                        session.setAdType(finalState.adType());
                        sessionManager.update(connectionId, session);
                        return Uni.createFrom().item(
                                new AdResult(AdResult.Action.ASK_QUESTION, null, finalState.nextQuestion()));
                    }
                });
    }

    private String buildDetailsQuestion(AdState state) {
        String category = state.userData().getOrDefault("category", "").toLowerCase();
        List<String> relevant = CATEGORY_DETAILS.getOrDefault(category,
                List.of("price", "location", "condition"));
        List<String> missing = relevant.stream()
                .filter(f -> state.userData().getOrDefault(f, "").isBlank())
                .toList();
        if (missing.isEmpty()) {
            return "Anything else you'd like to add? (feel free to skip)";
        }
        String fields = String.join(", ", missing);
        return "Could you also share " + fields + "? (feel free to skip if not applicable)";
    }

    public Uni<String> generateFirstQuestion(AdSessionData session) {
        String question = switch (session.getAdType() == null ? "" : session.getAdType()) {
            case "STORE_PROMO" -> "What's the promotion, and how long does it run?";
            case "CLASSIFIED" -> "What would you like to advertise? Tell me what you're promoting, key details, and your contact info.";
            default -> "What would you like to advertise or promote on the station?";
        };
        return Uni.createFrom().item(question);
    }

    private String generateTitle(String brandSlug, String description) {
        String fallback = description.length() > 50 ? description.substring(0, 50).trim() : description;
        try {
            LlmRequest request = LlmRequest.builder()
                    .model(llmProviderResolver.modelFor(brandSlug, LlmUseCase.FOLLOW_UP))
                    .maxTokens(30)
                    .addMessage(LlmMessage.text(LlmMessage.Role.USER,
                            "Write a short 3-5 word title summarizing this ad. Reply with the title only, no punctuation: " + description))
                    .build();
            LlmResponse response = llmProviderResolver.clientFor(brandSlug)
                    .createMessage(request).toCompletableFuture().join();
            return response.text() != null && !response.text().isBlank() ? response.text().trim() : fallback;
        } catch (Exception e) {
            LOGGER.warnf("Title generation failed: %s", e.getMessage());
            return fallback;
        }
    }

    private Uni<UUID> saveAd(AdSessionData session, AdState state) {
        Map<String, String> vars = state.collectedVars();
        String description = vars.getOrDefault("description", "");
        String contacts = vars.getOrDefault("contacts", "");
        String title = generateTitle(session.getBrandSlug(), description);

        UserAd ad = new UserAd();
        ad.setUserId(session.getUserId());
        ad.setBrandId(session.getBrandId());
        ad.setTitle(title);
        ad.setSlugName(com.semantyca.core.util.WebHelper.generateSlug(title));
        ad.setDescription(description);
        ad.setContacts(contacts);
        Map<String, String> udMap = new HashMap<>(session.getUserData());
        udMap.putAll(state.userData());
        if (state.adType() != null) {
            udMap.put("adType", state.adType());
        }
        String validity = vars.get("validity");
        if (validity != null && !validity.isBlank()) {
            udMap.put("validity", validity);
        }
        if (!udMap.isEmpty()) {
            com.semantyca.core.model.UserData ud = new com.semantyca.core.model.UserData();
            udMap.forEach(ud::put);
            ad.setUserData(ud);
        }

        return userAdRepository.insert(ad, new AnonymousUser())
                .invoke(adId -> LOGGER.infof("[AdGraph] UserAd saved id=%s", adId));
    }

    private Map<String, Object> buildStateMap(AdSessionData session, String userMessage) {
        Map<String, Object> state = new HashMap<>();
        state.put(AdState.USER_MESSAGE, userMessage);
        state.put(AdState.BRAND_SLUG, session.getBrandSlug());
        state.put(AdState.PENDING_VAR, session.getPendingVar() != null ? session.getPendingVar() : "");
        state.put(AdState.COLLECTED_VARS, new HashMap<>(session.getCollectedVars()));
        state.put(AdState.USER_DATA, new HashMap<>(session.getUserData()));
        state.put(AdState.AD_TYPE, session.getAdType() != null ? session.getAdType() : "");
        state.put(AdState.REQUIRED_VARS, new ArrayList<>(requiredVarsFor(session.getAdType())));
        return state;
    }
}
