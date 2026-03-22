package com.semantyca.jesoos.service.chat;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.Model;
import com.semantyca.jesoos.config.JesoosConfig;
import com.semantyca.jesoos.model.chat.ChatMessage;
import com.semantyca.jesoos.model.chat.ChatSummary;
import com.semantyca.jesoos.model.cnst.ChatType;
import com.semantyca.jesoos.repository.ChatRepository;
import com.semantyca.jesoos.repository.ChatSummaryRepository;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class ChatSummaryService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ChatSummaryService.class);

    private static final int BRAND_SUMMARY_THRESHOLD = 20;
    private static final int USER_SUMMARY_THRESHOLD = 20;
    private static final int MESSAGE_RETENTION_DAYS = 7;

    private final AnthropicClient anthropicClient;
    private final ChatRepository chatRepository;
    private final ChatSummaryRepository chatSummaryRepository;

    @Inject
    public ChatSummaryService(JesoosConfig config,
                              ChatRepository chatRepository,
                              ChatSummaryRepository chatSummaryRepository) {
        this.chatRepository = chatRepository;
        this.chatSummaryRepository = chatSummaryRepository;
        if (config != null && config.getAnthropicApiKey() != null) {
            this.anthropicClient = AnthropicOkHttpClient.builder()
                    .apiKey(config.getAnthropicApiKey())
                    .build();
        } else {
            this.anthropicClient = null;
        }
    }

    @Scheduled(every = "15m")
    public void scheduledBrandSummary() {
        if (anthropicClient == null) {
            return;
        }

        chatRepository.getActiveBrands()
                .subscribe().with(
                        brands -> brands.forEach(this::checkAndSummarizeBrand),
                        error -> LOGGER.error("Failed to get active brands for summarization", error)
                );
    }

    @Scheduled(every = "15m")
    public void scheduledUserSummary() {
        if (anthropicClient == null) {
            return;
        }

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
                        v -> LOGGER.info("Cleaned up old summarized messages older than {} days", MESSAGE_RETENTION_DAYS),
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
                                                v -> LOGGER.info("Summarized {} messages for brand {}", count, brandName),
                                                error -> LOGGER.error("Failed to summarize brand {}", brandName, error)
                                        );
                            }
                        },
                        error -> LOGGER.error("Failed to count messages for brand {}", brandName, error)
                );
    }

    private void checkAndSummarizeUser(ChatRepository.ActiveUserSession session) {
        chatRepository.countUnsummarizedUserMessages(session.userId(), session.brandName(), session.chatType())
                .subscribe().with(
                        count -> {
                            if (count >= USER_SUMMARY_THRESHOLD) {
                                summarizeUserMessages(session.userId(), session.brandName(), session.chatType())
                                        .subscribe().with(
                                                v -> LOGGER.info("Summarized {} messages for user {} on brand {}", count, session.userId(), session.brandName()),
                                                error -> LOGGER.error("Failed to summarize user {} on brand {}", session.userId(), session.brandName(), error)
                                        );
                            }
                        },
                        error -> LOGGER.error("Failed to count messages for user {} on brand {}", session.userId(), session.brandName(), error)
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
                                summary.setSummaryType(ChatSummary.SummaryType.BRAND);
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
                                summary.setSummaryType(ChatSummary.SummaryType.USER);
                                summary.setUserId(userId);
                                summary.setChatType(chatType);
                                summary.setSummary(summaryText);
                                summary.setMessageCount(toSummarize.size());
                                summary.setPeriodStart(toSummarize.get(0).getTimestamp());
                                summary.setPeriodEnd(toSummarize.get(toSummarize.size() - 1).getTimestamp());

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
        if (anthropicClient == null) {
            return Uni.createFrom().item("Summary generation unavailable");
        }

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
                    Be concise but preserve important details.
                    
                    Conversation:
                    """ + messagesText;
        }

        MessageCreateParams params = MessageCreateParams.builder()
                .maxTokens(500L)
                .model(Model.CLAUDE_HAIKU_4_5_20251001)
                .addMessage(MessageParam.builder()
                        .role(MessageParam.Role.USER)
                        .content(MessageParam.Content.ofString(prompt))
                        .build())
                .build();

        return Uni.createFrom().completionStage(() -> anthropicClient.async().messages().create(params))
                .map(this::extractTextFromResponse)
                .onFailure().recoverWithItem(error -> {
                    LOGGER.error("Failed to generate summary", error);
                    return "Summary generation failed";
                });
    }

    private String extractTextFromResponse(Message message) {
        return message.content().stream()
                .filter(ContentBlock::isText)
                .map(block -> block.asText().text())
                .collect(Collectors.joining("\n"));
    }
}
