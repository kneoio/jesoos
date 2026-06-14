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
import io.quarkus.scheduler.Scheduled;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class ChatSummaryService {
    private static final Logger LOGGER = Logger.getLogger(ChatSummaryService.class);

    private static final int BRAND_SUMMARY_THRESHOLD = 20;
    private static final int USER_SUMMARY_THRESHOLD = 20;
    private static final int MESSAGE_RETENTION_DAYS = 7;

    private final JesoosConfig config;
    private final AnthropicTextClient anthropicTextClient;
    private final GroqTextClient groqTextClient;
    private final ChatRepository chatRepository;
    private final ChatSummaryRepository chatSummaryRepository;

    @Inject
    public ChatSummaryService(JesoosConfig config,
                              AnthropicTextClient anthropicTextClient,
                              GroqTextClient groqTextClient,
                              ChatRepository chatRepository,
                              ChatSummaryRepository chatSummaryRepository) {
        this.config = config;
        this.anthropicTextClient = anthropicTextClient;
        this.groqTextClient = groqTextClient;
        this.chatRepository = chatRepository;
        this.chatSummaryRepository = chatSummaryRepository;
    }

    @Scheduled(every = "15m")
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
        chatRepository.countUnsummarizedMessages(brandName)
                .subscribe().with(
                        count -> {
                            if (count >= BRAND_SUMMARY_THRESHOLD) {
                                summarizeBrandMessages(brandName)
                                        .subscribe().with(
                                                v -> LOGGER.infof("Summarized %s messages for brand %s", count, brandName),
                                                error -> LOGGER.error("Failed to summarize brand %s", brandName, error)
                                        );
                            }
                        },
                        error -> LOGGER.error("Failed to count messages for brand %s", brandName, error)
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
                    return generateSummary(messagesText, "BRAND")
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
                    return generateSummary(messagesText, "USER")
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
        return chatSummaryRepository.getLatestBrandSummary(brandName)
                .map(opt -> opt.map(ChatSummary::getSummary).orElse(null));
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

    private Uni<String> generateSummary(String messagesText, String summaryType) {
        String prompt;
        if ("BRAND".equals(summaryType)) {
            prompt = """
                    Summarize the following public chat messages from radio listeners.
                    Extract key themes, common questions, song requests, mood, and any important topics.
                    This summary will be used by the DJ to understand what the audience is talking about.
                    Be concise but comprehensive. Format as bullet points.
                    
                    Messages:
                    """ + messagesText;
        } else {
            prompt = """
                    Summarize the following conversation history with a user.
                    Capture key topics discussed, user preferences, and any important context.
                    This summary will be used to maintain conversation context.
                    Keep the summary neutral and role-based.
                    Do not include or infer personal names, DJ persona names, usernames, or identity labels.
                    Refer to participants generically (for example: "listener", "host", "participants").
                    Be concise but preserve important details.
                    
                    Conversation:
                    """ + messagesText;
        }

        String provider = config.getSummaryLlmProvider();
        String model = "groq".equals(provider) ? config.getSummaryGroqModel() : config.getSummaryAnthropicModel();
        LlmTextClient llmTextClient = selectLlmClient(provider);
        return llmTextClient.createTextMessage(model, 500L, "You summarize chat history accurately.", prompt)
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
