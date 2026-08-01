package com.semantyca.jesoos.service.maintenance;

import com.semantyca.core.model.cnst.SummaryType;
import com.semantyca.jesoos.config.JesoosConfig;
import com.semantyca.core.llm.AnthropicTextClient;
import com.semantyca.core.llm.GroqTextClient;
import com.semantyca.core.llm.LlmTextClient;
import com.semantyca.core.llm.LlmTextResult;
import com.semantyca.jesoos.model.chat.ChatMessage;
import com.semantyca.jesoos.model.chat.ChatSummary;
import com.semantyca.jesoos.model.cnst.ChatType;
import com.semantyca.jesoos.repository.ChatRepository;
import com.semantyca.jesoos.repository.ChatSummaryRepository;
import com.semantyca.jesoos.messaging.MetricPublisher;
import com.semantyca.jesoos.service.ListenerService;
import com.semantyca.jesoos.service.chat.tools.ListenerLabelCache;
import com.semantyca.mixpla.dto.queue.metric.MetricEventType;
import com.semantyca.mixpla.dto.queue.metric.ProcessType;
import com.semantyca.core.model.UserData;
import com.semantyca.core.model.cnst.MessageType;
import com.semantyca.mixpla.model.Listener;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class ChatSummaryService {
    private static final Logger LOGGER = Logger.getLogger(ChatSummaryService.class);

    private static final int BRAND_SUMMARY_THRESHOLD = 20;
    private static final int USER_SUMMARY_THRESHOLD = 20;
    private static final int MESSAGE_RETENTION_DAYS = 7;
    /** Help chat is anonymous and never summarized, so its rows are reaped by age alone. */
    private static final int HELP_RETENTION_DAYS = 2;
    // A brand summary is on-air material: summarize the tail on age too, not only on volume,
    // otherwise a quiet station never crosses the count threshold and the DJ stays blind.
    private static final int BRAND_SUMMARY_MAX_TAIL_AGE_MINUTES = 10;
    // Beyond this the conversation is dead and must not be voiced as if it were current.
    private static final int BRAND_SUMMARY_MAX_AGE_MINUTES = 60;

    private final JesoosConfig config;
    private final AnthropicTextClient anthropicTextClient;
    private final GroqTextClient groqTextClient;
    private final ChatRepository chatRepository;
    private final ChatSummaryRepository chatSummaryRepository;
    private final ListenerService listenerService;
    private final ListenerLabelCache listenerLabelCache;
    private final MetricPublisher metricPublisher;

    @Inject
    public ChatSummaryService(JesoosConfig config,
                              AnthropicTextClient anthropicTextClient,
                              GroqTextClient groqTextClient,
                              ChatRepository chatRepository,
                              ChatSummaryRepository chatSummaryRepository,
                              ListenerService listenerService,
                              ListenerLabelCache listenerLabelCache,
                              MetricPublisher metricPublisher) {
        this.config = config;
        this.anthropicTextClient = anthropicTextClient;
        this.groqTextClient = groqTextClient;
        this.chatRepository = chatRepository;
        this.chatSummaryRepository = chatSummaryRepository;
        this.listenerService = listenerService;
        this.listenerLabelCache = listenerLabelCache;
        this.metricPublisher = metricPublisher;
    }

    public record BrandChatContext(UUID summaryId, String summary, boolean fresh) {
        public static BrandChatContext empty() {
            return new BrandChatContext(null, "", false);
        }

        public boolean usable() {
            return fresh && summaryId != null && !summary.isBlank();
        }
    }

    // Brands are summarized one after another, never fanned out: every brand needing a summary in
    // the same tick would otherwise fire a simultaneous LLM call and the provider rate-limits the
    // burst, which surfaces as whole batches failing to summarize at the same instant.
    @Scheduled(every = "5m")
    public void scheduledBrandSummary() {
        chatRepository.getActiveBrands()
                .onItem().transformToMulti(brands -> Multi.createFrom().iterable(brands))
                .onItem().transformToUniAndConcatenate(this::checkAndSummarizeBrand)
                .collect().asList()
                .subscribe().with(
                        v -> {},
                        error -> LOGGER.error("Failed to get active brands for summarization", error)
                );
    }

    @Scheduled(every = "15m")
    public void scheduledUserSummary() {
        chatRepository.getActiveUsers()
                .onItem().transformToMulti(users -> Multi.createFrom().iterable(users))
                .onItem().transformToUniAndConcatenate(this::checkAndSummarizeUser)
                .collect().asList()
                .subscribe().with(
                        v -> {},
                        error -> LOGGER.error("Failed to get active users for summarization", error)
                );
    }

    @Scheduled(cron = "0 0 3 * * ?")
    public void scheduledCleanup() {
        chatRepository.deleteOldSummarizedMessages(MESSAGE_RETENTION_DAYS)
                .subscribe().with(
                        v -> LOGGER.infof("Cleaned up old summarized messages older than %s days", MESSAGE_RETENTION_DAYS),
                        error -> LOGGER.error("Failed to cleanup old messages", error)
                );
        chatRepository.deleteOldMessagesByType(ChatType.HELP, HELP_RETENTION_DAYS)
                .subscribe().with(
                        v -> LOGGER.infof("Cleaned up help messages older than %s days", HELP_RETENTION_DAYS),
                        error -> LOGGER.error("Failed to cleanup old help messages", error)
                );
    }

    private Uni<Void> checkAndSummarizeBrand(String brandName) {
        return Uni.combine().all().unis(
                        chatRepository.countUnsummarizedMessages(brandName),
                        chatRepository.getOldestUnsummarizedMessageTime(brandName)
                )
                .asTuple()
                .flatMap(tuple -> {
                    int count = tuple.getItem1();
                    OffsetDateTime oldest = tuple.getItem2();
                    if (count == 0) {
                        return Uni.createFrom().voidItem();
                    }
                    boolean tailIsAging = oldest != null && Duration.between(
                            oldest, OffsetDateTime.now(ZoneOffset.UTC)
                    ).toMinutes() >= BRAND_SUMMARY_MAX_TAIL_AGE_MINUTES;
                    if (count < BRAND_SUMMARY_THRESHOLD && !tailIsAging) {
                        return Uni.createFrom().voidItem();
                    }
                    return summarizeBrandMessages(brandName)
                            .invoke(() -> LOGGER.infof("Summarized %s messages for brand %s (tailAging=%s)", count, brandName, tailIsAging));
                })
                // Never let one brand abort the sequence; the batch stays unsummarized and is retried.
                .onFailure().recoverWithItem(error -> {
                    LOGGER.error("Failed to summarize brand " + brandName, error);
                    return null;
                });
    }

    private Uni<Void> checkAndSummarizeUser(ChatRepository.ActiveUserSession session) {
        return chatRepository.countUnsummarizedUserMessages(session.userId(), session.brandName(), session.chatType())
                .flatMap(count -> {
                    if (count < USER_SUMMARY_THRESHOLD) {
                        return Uni.createFrom().voidItem();
                    }
                    return summarizeUserMessages(session.userId(), session.brandName(), session.chatType())
                            .invoke(() -> LOGGER.infof("Summarized %s messages for user %s on brand %s",
                                    count, session.userId(), session.brandName()));
                })
                .onFailure().recoverWithItem(error -> {
                    LOGGER.errorf("Failed to summarize user %s on brand %s: %s",
                            session.userId(), session.brandName(), error.getMessage());
                    return null;
                });
    }

    public Uni<Void> summarizeBrandMessages(String brandName) {
        return chatRepository.getUnsummarizedBrandMessages(brandName, 100)
                .flatMap(messages -> {
                    if (messages.isEmpty()) {
                        return Uni.createFrom().voidItem();
                    }

                    String messagesText = formatMessagesForSummary(messages);
                    return buildListenerProfiles(messages)
                            .flatMap(profiles -> generateBrandSummary(messagesText, profiles))
                            .onFailure().invoke(error ->
                                    publishSummaryFailure(brandName, SummaryType.BRAND, messages, error))
                            .flatMap(summaryText -> {
                                ChatSummary summary = new ChatSummary();
                                summary.setBrandName(brandName);
                                summary.setSummaryType(SummaryType.BRAND);
                                summary.setSummary(summaryText);
                                summary.setMessageCount(messages.size());
                                summary.setPeriodStart(messages.getFirst().getTimestamp());
                                summary.setPeriodEnd(messages.getLast().getTimestamp());

                                return chatSummaryRepository.save(summary)
                                        .flatMap(summaryId -> {
                                            List<UUID> messageIds = messages.stream()
                                                    .map(ChatMessage::getId)
                                                    .collect(Collectors.toList());
                                            return chatRepository.markMessagesAsSummarized(messageIds, summaryId);
                                        })
                                        .invoke(() -> publishSummaryCreated(brandName, SummaryType.BRAND,
                                                messages, summaryText));
                            });
                });
    }

    public Uni<Void> summarizeUserMessages(long userId, String brandName, ChatType chatType) {
        return chatRepository.getUnsummarizedUserMessages(userId, brandName, chatType, 100)
                .flatMap(messages -> {
                    if (messages.size() < USER_SUMMARY_THRESHOLD) {
                        return Uni.createFrom().voidItem();
                    }

                    int messagesToSummarize = messages.size() - 5;
                    List<ChatMessage> toSummarize = messages.subList(0, messagesToSummarize);

                    String messagesText = formatMessagesForSummary(toSummarize);
                    SummaryType summaryType = summaryTypeFor(chatType);
                    // Ask is a platform Q&A, not a listener relationship: no DJ persona, no profiles.
                    Uni<String> generation = chatType == ChatType.ASK
                            ? generateAskSummary(messagesText)
                            : buildListenerProfiles(toSummarize)
                                    .flatMap(profiles -> generateUserSummary(messagesText, profiles));
                    return generation
                            .onFailure().invoke(error ->
                                    publishSummaryFailure(brandName, summaryType, toSummarize, error))
                            .flatMap(summaryText -> {
                                ChatSummary summary = new ChatSummary();
                                summary.setBrandName(brandName);
                                summary.setSummaryType(summaryType);
                                summary.setUserId(userId);
                                summary.setChatType(chatType);
                                summary.setSummary(summaryText);
                                summary.setMessageCount(toSummarize.size());
                                summary.setPeriodStart(toSummarize.getFirst().getTimestamp());
                                summary.setPeriodEnd(toSummarize.getLast().getTimestamp());

                                return chatSummaryRepository.save(summary)
                                        .flatMap(summaryId -> {
                                            List<UUID> messageIds = toSummarize.stream()
                                                    .map(ChatMessage::getId)
                                                    .collect(Collectors.toList());
                                            return chatRepository.markMessagesAsSummarized(messageIds, summaryId);
                                        })
                                        .invoke(() -> publishSummaryCreated(brandName, summaryType,
                                                toSummarize, summaryText));
                            });
                });
    }

    /**
     * Summarization failures are invisible in the flow otherwise: nothing is persisted and the DJ
     * simply stays silent about chat. metriq needs the provider/model/batch detail to tell a rate
     * limit apart from a bad batch.
     */
    private void publishSummaryFailure(String brandName, SummaryType summaryType,
                                       List<ChatMessage> messages, Throwable error) {
        String provider = config.getSummaryLlmProvider();
        Map<String, Object> payload = new HashMap<>();
        payload.put("summaryType", summaryType.name());
        payload.put("messageCount", messages.size());
        payload.put("periodStart", String.valueOf(messages.getFirst().getTimestamp()));
        payload.put("periodEnd", String.valueOf(messages.getLast().getTimestamp()));
        payload.put("provider", provider);
        payload.put("model", resolveSummaryModel(provider));
        payload.put("errorType", error.getClass().getSimpleName());
        payload.put("error", error.getMessage() != null ? error.getMessage() : "");
        metricPublisher.publishMetric(brandName, MetricEventType.ERROR, ProcessType.CRON,
                "chat_summary_failed", payload);
    }

    private void publishSummaryCreated(String brandName, SummaryType summaryType,
                                       List<ChatMessage> messages, String summaryText) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("summaryType", summaryType.name());
        payload.put("messageCount", messages.size());
        payload.put("summaryLength", summaryText.length());
        payload.put("periodStart", String.valueOf(messages.getFirst().getTimestamp()));
        payload.put("periodEnd", String.valueOf(messages.getLast().getTimestamp()));
        metricPublisher.publishMetric(brandName, MetricEventType.INFORMATION, ProcessType.CRON,
                "chat_summary_created", payload);
    }

    public Uni<String> getLatestBrandSummary(String brandName) {
        return getBrandChatContext(brandName).map(ctx -> ctx.fresh() ? ctx.summary() : null);
    }

    /**
     * Brand chat context for the air agent, with the age of the conversation it describes.
     * A summary older than {@link #BRAND_SUMMARY_MAX_AGE_MINUTES} is reported as not fresh so the
     * DJ never voices a dead conversation as if it were happening now.
     */
    public Uni<BrandChatContext> getBrandChatContext(String brandName) {
        return chatSummaryRepository.getLatestBrandSummary(brandName)
                .map(opt -> opt.map(summary -> {
                    OffsetDateTime reference = summary.getPeriodEnd() != null
                            ? summary.getPeriodEnd()
                            : summary.getCreatedAt();
                    long ageMinutes = reference != null
                            ? Duration.between(reference, OffsetDateTime.now(ZoneOffset.UTC)).toMinutes()
                            : Long.MAX_VALUE;
                    boolean fresh = ageMinutes <= BRAND_SUMMARY_MAX_AGE_MINUTES;
                    return new BrandChatContext(
                            summary.getId(),
                            summary.getSummary() != null ? summary.getSummary() : "",
                            fresh
                    );
                }).orElseGet(BrandChatContext::empty));
    }

    /**
     * Marks a brand summary as voiced on air so it is never handed to the DJ again.
     */
    public Uni<Void> markBrandSummaryAired(UUID summaryId) {
        return chatSummaryRepository.markAsAired(summaryId);
    }

    /**
     * Resolves the listener behind every distinct speaker in the batch so the summary can carry
     * who these people are — the {@code userData} the chat bot collected during the conversation
     * plus their labels (artist, owner, …) — not only what they said.
     */
    private Uni<String> buildListenerProfiles(List<ChatMessage> messages) {
        List<Long> userIds = messages.stream()
                .filter(msg -> msg.getMessageType() == MessageType.USER)
                .map(ChatMessage::getUserId)
                .filter(id -> id != null && id != 0L)
                .distinct()
                .collect(Collectors.toList());

        if (userIds.isEmpty()) {
            return Uni.createFrom().item("");
        }

        return Multi.createFrom().iterable(userIds)
                .onItem().transformToUniAndConcatenate(userId -> listenerService.getByUserId(userId)
                        .map(this::formatListenerProfile)
                        .onFailure().recoverWithItem(error -> {
                            LOGGER.warnf("Failed to resolve listener %s for summarization: %s", userId, error.getMessage());
                            return null;
                        }))
                .filter(Objects::nonNull)
                .collect().asList()
                .map(profiles -> String.join("\n", profiles));
    }

    private String formatListenerProfile(Listener listener) {
        if (listener == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder("- ");
        String name = null;
        UserData userData = listener.getUserData();
        if (userData != null && userData.getData() != null) {
            name = userData.getData().get("preferred_name");
        }
        if ((name == null || name.isBlank()) && listener.getLocalizedName() != null) {
            name = listener.getLocalizedName().values().stream()
                    .filter(v -> v != null && !v.isBlank())
                    .findFirst().orElse(null);
        }
        sb.append(name != null && !name.isBlank() ? name : "unknown").append(":");
        if (userData != null && userData.getData() != null) {
            userData.getData().forEach((k, v) -> {
                if (v != null && !v.isBlank()) {
                    sb.append(" ").append(k).append("=").append(v).append(";");
                }
            });
        }
        List<String> resolvedLabels = listenerLabelCache.resolveToIdentifiers(listener.getLabels());
        if (!resolvedLabels.isEmpty()) {
            sb.append(" labels=").append(resolvedLabels).append(";");
        }
        return sb.toString();
    }

    public Uni<String> getLatestUserSummary(long userId, String brandName, ChatType chatType) {
        return chatSummaryRepository.getLatestUserSummary(userId, brandName, chatType, summaryTypeFor(chatType))
                .map(opt -> opt.map(ChatSummary::getSummary).orElse(null));
    }

    /** Ask conversations are their own summary type; every other chat keeps the listener USER type. */
    private static SummaryType summaryTypeFor(ChatType chatType) {
        return chatType == ChatType.ASK ? SummaryType.ASK : SummaryType.USER;
    }

    /**
     * Roles are labelled explicitly. The bot posts under the DJ persona name, so an unlabelled
     * transcript makes the host look like just another listener — and the summary then tells the
     * air DJ to greet itself.
     */
    private String formatMessagesForSummary(List<ChatMessage> messages) {
        StringBuilder sb = new StringBuilder();
        for (ChatMessage msg : messages) {
            if (msg.getMessageType() != MessageType.USER && msg.getMessageType() != MessageType.BOT) {
                continue;
            }
            String role = msg.getMessageType() == MessageType.BOT ? "HOST (you)" : "LISTENER";
            sb.append("[").append(msg.getTimestamp()).append("] ");
            sb.append(role).append(" ").append(msg.getUsername()).append(": ");
            sb.append(msg.getContent()).append("\n");
        }
        return sb.toString();
    }

    private Uni<String> generateBrandSummary(String messagesText, String listenerProfiles) {
        String prompt = """
                You prepare ready-to-use on-air context for a radio DJ.

                The DJ is the same persona the listeners were just talking to in the station chat.
                Chat and air are one person to the listener, so the DJ must sound like someone who
                remembers the conversation personally and knows who these people are.

                Busy chat is common: many listeners may have spoken in the same batch. Do NOT roll-call
                everyone. Rank candidates and keep only the best for one short on-air breath.

                Priority (high to low) — pick from the top of this list first:
                1. Song requests, dedications, or anything a listener asked to have said on air
                2. Listeners with labels (artist, owner) or a clear identity worth naming on air
                3. One vivid personal detail (city, occasion) that makes a warm hello natural
                4. Drop the rest

                Within a tier (and when candidates are otherwise equal), prefer posts that would sound
                good on air — specific, warm, surprising, or emotionally clear — over flat hellos,
                small talk, or support. Prefer concrete asks over vague chatter. This is best-effort
                judgment, not perfect fairness: do not invent interest; if unsure, prefer actionable
                on-air material (requests) over what merely "sounds fun".

                Hard shape:
                - At most 3 listeners in the whole summary
                - One short bullet per chosen listener: name to address them by, who they are
                  (profile / labels when useful), what they said or asked for, and anything already
                  promised or answered in chat so the DJ does not repeat it
                - Prefer people with actionable on-air material over "everyone present"
                - Never fill the quota with weak material
                - Then optionally one short line on the overall mood of the room
                - If nothing is speakable, output nothing (or a single empty-feeling line the DJ
                  can ignore) — never invent listeners to fill the quota

                Rules:
                - Keep first names and the human details the DJ needs to address someone warmly.
                - NEVER include contact or private data, even when a listener posted it in chat:
                  no phone numbers, emails, street addresses, full names, prices, or payment details.
                  This text is spoken on a public broadcast. If a listener placed an ad, say that an
                  ad was arranged — never read back its contents.
                - Lines labelled "HOST (you)" are the DJ's own earlier messages. Use them only to know
                  what was already answered or promised. Never describe the host as a listener and
                  never list the host as someone to greet.
                - Skip anonymous listeners and anyone whose only name is a handle or has digits in it
                  (for example "user438"): they cannot be addressed naturally on air.
                - Only state what the messages and profiles support. Never invent a detail.
                - Prefer concrete, speakable facts over abstract themes. "Mira, an artist from
                  Michigan, asked for something upbeat" is useful; "listeners discussed music" is not.
                - Drop small talk, and drop technical support exchanges about the site or uploads —
                  they give the DJ nothing to say on air.
                - Be concise. Format as bullet points.

                Listener profiles (collected by the chat bot during these conversations):
                """ + (listenerProfiles.isBlank() ? "(none available)" : listenerProfiles) + """

                Messages:
                """ + messagesText;
        return callSummaryLlm(prompt);
    }

    private Uni<String> generateUserSummary(String messagesText, String listenerProfiles) {
        String prompt = """
                Summarize the following conversation history with a listener.

                This summary keeps the persona continuous: the same character talks to this listener
                in chat and on air, and must remember them next time. Capture who the listener is —
                the details in their profile, their labels (artist, owner, …), their preferences —
                along with the key topics discussed and anything that was promised or agreed.

                Keep names and identifying details; they are what makes the persona recognisable.
                Lines labelled "HOST (you)" are your own earlier replies, not the listener's words.
                Only state what the messages and profile support. Never invent a detail.
                Be concise but preserve the details that matter for the next conversation.

                Listener profile (collected by the chat bot during these conversations):
                """ + (listenerProfiles.isBlank() ? "(none available)" : listenerProfiles) + """

                Conversation:
                """ + messagesText;
        return callSummaryLlm(prompt);
    }

    /**
     * Ask continuity: what this person is trying to understand, not who they are on air.
     */
    private Uni<String> generateAskSummary(String messagesText) {
        String prompt = """
                Summarize the following conversation between a Mixpla user and the platform
                assistant Mixplaclone.

                This summary is read back at the start of their next Ask session so the assistant can
                pick up where it left off. Capture what they were trying to understand or do, which
                parts of the platform came up, the level of depth that suited them, any decision or
                recommendation given, and any question left unanswered.

                Lines labelled "HOST (you)" are the assistant's own earlier replies.
                This is a support conversation, not a broadcast: no on-air phrasing, no persona, no
                small talk, and nothing about the person beyond what they said about their own use of
                Mixpla. Only state what the messages support. Never invent a detail.
                Be concise. Format as bullet points.

                Conversation:
                """ + messagesText;
        return callSummaryLlm(prompt);
    }

    private Uni<String> callSummaryLlm(String prompt) {
        String provider = config.getSummaryLlmProvider();
        String model = resolveSummaryModel(provider);
        String apiKey = "groq".equals(provider) ? config.getGroqApiKey().orElse("") : config.getAnthropicApiKey();
        LlmTextClient llmTextClient = selectLlmClient(provider);
        // Deliberately fail-loud: a failed generation must NOT be persisted. Saving a placeholder
        // would mark the messages summarized — losing them for good — and hand the DJ an error
        // string as on-air chat context. Failing leaves the batch unsummarized for the next tick.
        return llmTextClient.createTextMessage(apiKey, model, 700L,
                        "You prepare accurate, speakable context from chat history. You never invent facts.", prompt)
                .map(LlmTextResult::text)
                .onItem().transform(text -> {
                    if (text == null || text.isBlank()) {
                        throw new IllegalStateException("Summary LLM returned empty text");
                    }
                    return text;
                })
                .onFailure().invoke(error -> LOGGER.error("Failed to generate summary", error));
    }

    private String resolveSummaryModel(String provider) {
        return "groq".equals(provider) ? config.getSummaryGroqModel() : config.getSummaryAnthropicModel();
    }

    private LlmTextClient selectLlmClient(String provider) {
        return "groq".equals(provider) ? groqTextClient : anthropicTextClient;
    }
}
