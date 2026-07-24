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
import com.semantyca.jesoos.service.ListenerService;
import com.semantyca.jesoos.service.chat.tools.ListenerLabelCache;
import com.semantyca.core.model.UserData;
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
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class ChatSummaryService {
    private static final Logger LOGGER = Logger.getLogger(ChatSummaryService.class);

    private static final int BRAND_SUMMARY_THRESHOLD = 20;
    private static final int USER_SUMMARY_THRESHOLD = 20;
    private static final int MESSAGE_RETENTION_DAYS = 7;
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

    @Inject
    public ChatSummaryService(JesoosConfig config,
                              AnthropicTextClient anthropicTextClient,
                              GroqTextClient groqTextClient,
                              ChatRepository chatRepository,
                              ChatSummaryRepository chatSummaryRepository,
                              ListenerService listenerService,
                              ListenerLabelCache listenerLabelCache) {
        this.config = config;
        this.anthropicTextClient = anthropicTextClient;
        this.groqTextClient = groqTextClient;
        this.chatRepository = chatRepository;
        this.chatSummaryRepository = chatSummaryRepository;
        this.listenerService = listenerService;
        this.listenerLabelCache = listenerLabelCache;
    }

    public record BrandChatContext(UUID summaryId, String summary, boolean fresh) {
        public static BrandChatContext empty() {
            return new BrandChatContext(null, "", false);
        }

        public boolean usable() {
            return fresh && summaryId != null && !summary.isBlank();
        }
    }

    @Scheduled(every = "5m")
    public void scheduledBrandSummary() {
        chatRepository.getActiveBrands()
                .subscribe().with(
                        brands -> brands.forEach(this::checkAndSummarizeBrand),
                        error -> LOGGER.error("Failed to get active brands for summarization", error)
                );
    }

    @Scheduled(every = "15m")
    public void scheduledUserSummary() {
        chatRepository.getActiveUsers()
                .subscribe().with(
                        users -> users.forEach(this::checkAndSummarizeUser),
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
    }

    private void checkAndSummarizeBrand(String brandName) {
        Uni.combine().all().unis(
                        chatRepository.countUnsummarizedMessages(brandName),
                        chatRepository.getOldestUnsummarizedMessageTime(brandName)
                )
                .asTuple()
                .subscribe().with(
                        tuple -> {
                            int count = tuple.getItem1();
                            OffsetDateTime oldest = tuple.getItem2();
                            if (count == 0) {
                                return;
                            }
                            boolean tailIsAging = oldest != null && Duration.between(
                                    oldest, OffsetDateTime.now(ZoneOffset.UTC)
                            ).toMinutes() >= BRAND_SUMMARY_MAX_TAIL_AGE_MINUTES;
                            if (count >= BRAND_SUMMARY_THRESHOLD || tailIsAging) {
                                summarizeBrandMessages(brandName)
                                        .subscribe().with(
                                                v -> LOGGER.infof("Summarized %s messages for brand %s (tailAging=%s)", count, brandName, tailIsAging),
                                                error -> LOGGER.error("Failed to summarize brand %s", brandName, error)
                                        );
                            }
                        },
                        error -> LOGGER.error("Failed to inspect unsummarized messages for brand %s", brandName, error)
                );
    }

    private void checkAndSummarizeUser(ChatRepository.ActiveUserSession session) {
        chatRepository.countUnsummarizedUserMessages(session.userId(), session.brandName(), session.chatType())
                .subscribe().with(
                        count -> {
                            if (count >= USER_SUMMARY_THRESHOLD) {
                                summarizeUserMessages(session.userId(), session.brandName(), session.chatType())
                                        .subscribe().with(
                                                v -> LOGGER.infof("Summarized %s messages for user %s on brand %s", count, session.userId(), session.brandName()),
                                                error -> LOGGER.errorf("Failed to summarize user %s on brand %s", session.userId(), session.brandName(), error)
                                        );
                            }
                        },
                        error -> LOGGER.errorf("Failed to count messages for user %s on brand %s", session.userId(), session.brandName(), error)
                );
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
                                        });
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
                    return buildListenerProfiles(toSummarize)
                            .flatMap(profiles -> generateUserSummary(messagesText, profiles))
                            .flatMap(summaryText -> {
                                ChatSummary summary = new ChatSummary();
                                summary.setBrandName(brandName);
                                summary.setSummaryType(SummaryType.USER);
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
                                        });
                            });
                });
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
        return chatSummaryRepository.getLatestUserSummary(userId, brandName, chatType)
                .map(opt -> opt.map(ChatSummary::getSummary).orElse(null));
    }

    private String formatMessagesForSummary(List<ChatMessage> messages) {
        StringBuilder sb = new StringBuilder();
        for (ChatMessage msg : messages) {
            sb.append("[").append(msg.getTimestamp()).append("] ");
            sb.append(msg.getUsername()).append(": ");
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

                Write the context so the DJ can voice it naturally between songs. For each listener
                worth mentioning, keep together:
                - the name to address them by
                - who they are: anything the profile says about them (where they are from, their
                  interests, their role) and their labels — an artist or an owner is worth naming
                  as such on air
                - what they actually said or asked for, and any song request
                - anything already promised or answered in chat, so the DJ does not repeat it

                Then add a short line on the overall mood of the room.

                Rules:
                - Keep real names and identifying details. The DJ needs them to address people.
                - Only state what the messages and profiles support. Never invent a detail.
                - Prefer concrete, speakable facts over abstract themes. "Mira, an artist from
                  Michigan, asked for something upbeat" is useful; "listeners discussed music" is not.
                - Drop small talk that gives the DJ nothing to say.
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
                Only state what the messages and profile support. Never invent a detail.
                Be concise but preserve the details that matter for the next conversation.

                Listener profile (collected by the chat bot during these conversations):
                """ + (listenerProfiles.isBlank() ? "(none available)" : listenerProfiles) + """

                Conversation:
                """ + messagesText;
        return callSummaryLlm(prompt);
    }

    private Uni<String> callSummaryLlm(String prompt) {
        String provider = config.getSummaryLlmProvider();
        String model = "groq".equals(provider) ? config.getSummaryGroqModel() : config.getSummaryAnthropicModel();
        String apiKey = "groq".equals(provider) ? config.getGroqApiKey().orElse("") : config.getAnthropicApiKey();
        LlmTextClient llmTextClient = selectLlmClient(provider);
        return llmTextClient.createTextMessage(apiKey, model, 700L,
                        "You prepare accurate, speakable context from chat history. You never invent facts.", prompt)
                .map(LlmTextResult::text)
                .onFailure().recoverWithItem(error -> {
                    LOGGER.error("Failed to generate summary", error);
                    return "Summary generation failed";
                });
    }

    private LlmTextClient selectLlmClient(String provider) {
        return "groq".equals(provider) ? groqTextClient : anthropicTextClient;
    }
}
