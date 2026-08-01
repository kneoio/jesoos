package com.semantyca.jesoos.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.semantyca.core.model.cnst.MessageType;
import com.semantyca.core.repository.AsyncRepository;
import com.semantyca.core.repository.rls.RLSRepository;
import com.semantyca.core.repository.table.EntityData;
import com.semantyca.jesoos.model.chat.ChatMessage;
import com.semantyca.jesoos.model.chat.ChatMessageEnvelope;
import com.semantyca.jesoos.model.cnst.ChatType;
import com.semantyca.jesoos.service.chat.llm.LlmMessage;
import com.semantyca.mixpla.repository.MixplaNameResolver;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static com.semantyca.mixpla.repository.MixplaNameResolver.CHAT_MESSAGE;


@ApplicationScoped
public class ChatRepository extends AsyncRepository {
    private static final Logger LOGGER = LoggerFactory.getLogger(ChatRepository.class);
    private static final EntityData entityData = MixplaNameResolver.create().getEntityNames(CHAT_MESSAGE);

    private final ConcurrentHashMap<String, List<LlmMessage>> conversationHistoryCache = new ConcurrentHashMap<>();

    @Inject
    public ChatRepository(Pool client, ObjectMapper mapper, RLSRepository rlsRepository) {
        super(client, mapper, rlsRepository);
    }

    public Uni<Void> saveChatMessage(long userId, String brandName, ChatType chatType, ChatMessageEnvelope message) {
        String sql = "INSERT INTO " + entityData.getTableName() +
                " (id, user_id, brand_name, chat_type, message_type, username, content, connection_id, timestamp) " +
                "VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)";

        OffsetDateTime timestamp = java.time.Instant.ofEpochMilli(message.timestamp()).atOffset(ZoneOffset.UTC);

        return client.preparedQuery(sql)
                .execute(Tuple.of(UUID.randomUUID(), userId, brandName, chatType.name(), message.type().name(), message.username())
                        .addString(message.content())
                        .addString(message.connectionId())
                        .addOffsetDateTime(timestamp))
                .replaceWithVoid()
                .onFailure().invoke(throwable ->
                    LOGGER.error("Failed to save chat message for user {} and chatType {}", userId, chatType, throwable)
                );
    }

    public static String connectionKey(String connectionId) {
        return "conn_" + connectionId;
    }

    public static String userKey(long userId) {
        return "user_" + userId;
    }

    /** @deprecated use connectionKey / userKey */
    @Deprecated
    public static String sessionKey(long userId, String connectionId, ChatType chatType) {
        String base = (userId == 0 ? connectionId : String.valueOf(userId));
        return base + "_" + chatType.name();
    }

    public void bootstrapConnectionFromUser(String connectionId, long userId) {
        if (userId == 0) return;
        String connKey = connectionKey(connectionId);
        if (conversationHistoryCache.containsKey(connKey)) return;
        List<LlmMessage> userHistory = conversationHistoryCache.get(userKey(userId));
        if (userHistory != null && !userHistory.isEmpty()) {
            conversationHistoryCache.put(connKey, new ArrayList<>(userHistory));
            LOGGER.info("[history] bootstrapped conn={} from userId={} size={}", connectionId, userId, userHistory.size());
        }
    }

    public void persistConnectionToUser(String connectionId, long userId) {
        if (userId == 0) return;
        List<LlmMessage> connHistory = conversationHistoryCache.get(connectionKey(connectionId));
        if (connHistory != null && !connHistory.isEmpty()) {
            conversationHistoryCache.put(userKey(userId), new ArrayList<>(connHistory));
            LOGGER.info("[history] persisted conn={} to userId={} size={}", connectionId, userId, connHistory.size());
        }
    }

    public Uni<List<JsonObject>> getRecentChatMessages(long userId, String connectionId, String brandName, ChatType chatType, int limit) {
        final String sql;
        final Tuple params;
        if (userId == 0) {
            sql = "SELECT * FROM " + entityData.getTableName() +
                    " WHERE connection_id = $1 AND brand_name = $2 AND chat_type = $3 " +
                    "ORDER BY timestamp DESC LIMIT $4";
            params = Tuple.of(connectionId, brandName, chatType.name(), limit);
        } else {
            sql = "SELECT * FROM " + entityData.getTableName() +
                    " WHERE user_id = $1 AND brand_name = $2 AND chat_type = $3 " +
                    "ORDER BY timestamp DESC LIMIT $4";
            params = Tuple.of(userId, brandName, chatType.name(), limit);
        }

        return client.preparedQuery(sql)
                .execute(params)
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transform(this::rowToJsonObject)
                .collect().asList()
                .onItem().transform(list -> {
                    List<JsonObject> reversed = new ArrayList<>(list);
                    java.util.Collections.reverse(reversed);
                    return reversed;
                });
    }

    public List<LlmMessage> getConversationHistory(String sessionKey) {
        return conversationHistoryCache.computeIfAbsent(sessionKey, k -> new ArrayList<>());
    }

    public void appendToConversation(String sessionKey, LlmMessage message) {
        conversationHistoryCache.computeIfAbsent(sessionKey, k -> new ArrayList<>()).add(message);
    }

    public void clearConversationHistory(String sessionKey) {
        conversationHistoryCache.remove(sessionKey);
    }

    public void replaceConversationHistory(String sessionKey, List<LlmMessage> history) {
        conversationHistoryCache.put(sessionKey, new ArrayList<>(history));
    }

    public Uni<Void> migrateAnonymousDbRecords(String connectionId, long newUserId) {
        String sql = "UPDATE " + entityData.getTableName() +
                " SET user_id = $1 WHERE connection_id = $2 AND user_id = 0";
        return client.preparedQuery(sql)
                .execute(Tuple.of(newUserId, connectionId))
                .replaceWithVoid()
                .onFailure().invoke(t ->
                        LOGGER.error("Failed to migrate anonymous chat for connection {} to user {}", connectionId, newUserId, t)
                );
    }

    private JsonObject rowToJsonObject(Row row) {
        return new JsonObject()
                .put("type", "message")
                .put("data", new JsonObject()
                        .put("type", row.getString("message_type"))
                        .put("id", row.getUUID("id").toString())
                        .put("brandName", row.getString("brand_name"))
                        .put("username", row.getString("username"))
                        .put("content", row.getString("content"))
                        .put("timestamp", row.getLocalDateTime("timestamp")
                                .toInstant(ZoneOffset.UTC)
                                .toEpochMilli())
                        .put("connectionId", row.getString("connection_id"))
                );
    }

    public Uni<ChatMessage> from(Row row) {
        ChatMessage entity = new ChatMessage();
        entity.setId(row.getUUID("id"));
        entity.setUserId(row.getLong("user_id"));
        entity.setBrandName(row.getString("brand_name"));
        entity.setChatType(ChatType.valueOf(row.getString("chat_type")));
        entity.setMessageType(MessageType.valueOf(row.getString("message_type")));
        entity.setUsername(row.getString("username"));
        entity.setContent(row.getString("content"));
        entity.setConnectionId(row.getString("connection_id"));
        entity.setTimestamp(row.getLocalDateTime("timestamp").atOffset(ZoneOffset.UTC));
        java.time.LocalDateTime summarizedAtRaw = row.getLocalDateTime("summarized_at");
        entity.setSummarizedAt(summarizedAtRaw != null ? summarizedAtRaw.atOffset(ZoneOffset.UTC) : null);
        entity.setSummaryId(row.getUUID("summary_id"));
        return Uni.createFrom().item(entity);
    }

    public Uni<List<ChatMessage>> getUnsummarizedBrandMessages(String brandName, int limit) {
        String sql = "SELECT * FROM " + entityData.getTableName() +
                " WHERE brand_name = $1 AND chat_type = 'PUBLIC' AND summarized_at IS NULL " +
                "ORDER BY timestamp ASC LIMIT $2";

        return client.preparedQuery(sql)
                .execute(Tuple.of(brandName, limit))
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transformToUni(this::from)
                .concatenate()
                .collect().asList();
    }

    public Uni<List<ChatMessage>> getUnsummarizedUserMessages(long userId, String brandName, ChatType chatType, int limit) {
        String sql = "SELECT * FROM " + entityData.getTableName() +
                " WHERE user_id = $1 AND brand_name = $2 AND chat_type = $3 AND summarized_at IS NULL " +
                "ORDER BY timestamp ASC LIMIT $4";

        return client.preparedQuery(sql)
                .execute(Tuple.of(userId, brandName, chatType.name(), limit))
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transformToUni(this::from)
                .concatenate()
                .collect().asList();
    }

    public Uni<Integer> countUnsummarizedMessages(String brandName) {
        String sql = "SELECT COUNT(*) as cnt FROM " + entityData.getTableName() +
                " WHERE brand_name = $1 AND chat_type = 'PUBLIC' AND summarized_at IS NULL";

        return client.preparedQuery(sql)
                .execute(Tuple.of(brandName))
                .onItem().transform(rows -> rows.iterator().next().getInteger("cnt"));
    }

    public Uni<OffsetDateTime> getOldestUnsummarizedMessageTime(String brandName) {
        String sql = "SELECT MIN(timestamp) AS oldest FROM " + entityData.getTableName() +
                " WHERE brand_name = $1 AND chat_type = 'PUBLIC' AND summarized_at IS NULL";

        return client.preparedQuery(sql)
                .execute(Tuple.of(brandName))
                .onItem().transform(rows -> {
                    java.time.LocalDateTime oldest = rows.iterator().next().getLocalDateTime("oldest");
                    return oldest != null ? oldest.atOffset(ZoneOffset.UTC) : null;
                });
    }

    public Uni<Integer> countUnsummarizedUserMessages(long userId, String brandName, ChatType chatType) {
        String sql = "SELECT COUNT(*) as cnt FROM " + entityData.getTableName() +
                " WHERE user_id = $1 AND brand_name = $2 AND chat_type = $3 AND summarized_at IS NULL";

        return client.preparedQuery(sql)
                .execute(Tuple.of(userId, brandName, chatType.name()))
                .onItem().transform(rows -> rows.iterator().next().getInteger("cnt"));
    }

    public Uni<Void> markMessagesAsSummarized(List<UUID> messageIds, UUID summaryId) {
        if (messageIds.isEmpty()) {
            return Uni.createFrom().voidItem();
        }

        String placeholders = messageIds.stream()
                .map(id -> "'" + id.toString() + "'")
                .reduce((a, b) -> a + "," + b)
                .orElse("");

        String sql = "UPDATE " + entityData.getTableName() +
                " SET summarized_at = $1, summary_id = $2 WHERE id IN (" + placeholders + ")";

        return client.preparedQuery(sql)
                .execute(Tuple.of(OffsetDateTime.now(ZoneOffset.UTC), summaryId))
                .replaceWithVoid()
                .onFailure().invoke(throwable ->
                        LOGGER.error("Failed to mark messages as summarized", throwable)
                );
    }

    // Ephemeral OTS chat: hard-delete every message for an event stream on teardown. The OTS slug
    // doubles as the event access token and never collides with a brand name, so brand_name = $1 is safe.
    public Uni<Void> deleteOtsMessages(String otsSlug) {
        String sql = "DELETE FROM " + entityData.getTableName() +
                " WHERE brand_name = $1 AND chat_type = '" + ChatType.OTS.name() + "'";
        return client.preparedQuery(sql)
                .execute(Tuple.of(otsSlug))
                .replaceWithVoid()
                .onFailure().invoke(throwable ->
                        LOGGER.error("Failed to delete OTS chat messages for {}", otsSlug, throwable)
                );
    }

    public Uni<Void> deleteOldSummarizedMessages(int daysOld) {
        String sql = "DELETE FROM " + entityData.getTableName() +
                " WHERE summarized_at IS NOT NULL AND summarized_at < $1";

        OffsetDateTime cutoff = OffsetDateTime.now(ZoneOffset.UTC).minusDays(daysOld);

        return client.preparedQuery(sql)
                .execute(Tuple.of(cutoff))
                .replaceWithVoid()
                .onFailure().invoke(throwable ->
                        LOGGER.error("Failed to delete old summarized messages", throwable)
                );
    }

    /**
     * Age-based retention for chat types that are never summarized — {@link #deleteOldSummarizedMessages}
     * only reaps rows carrying a summarized_at, so these would otherwise be kept forever.
     */
    public Uni<Void> deleteOldMessagesByType(ChatType chatType, int daysOld) {
        String sql = "DELETE FROM " + entityData.getTableName() +
                " WHERE chat_type = $1 AND timestamp < $2";

        OffsetDateTime cutoff = OffsetDateTime.now(ZoneOffset.UTC).minusDays(daysOld);

        return client.preparedQuery(sql)
                .execute(Tuple.of(chatType.name(), cutoff))
                .replaceWithVoid()
                .onFailure().invoke(throwable ->
                        LOGGER.error("Failed to delete old {} messages", chatType, throwable)
                );
    }

    public Uni<List<String>> getActiveBrands() {
        String sql = "SELECT DISTINCT brand_name FROM " + entityData.getTableName() +
                " WHERE chat_type = 'PUBLIC' AND summarized_at IS NULL";

        return client.preparedQuery(sql)
                .execute()
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transform(row -> row.getString("brand_name"))
                .collect().asList();
    }

    /**
     * Anonymous rows (user_id 0) have no one to summarize for, and HELP is never summarized —
     * without both filters the HELP chat would be swept in as a phantom "user 0" session.
     */
    public Uni<List<ActiveUserSession>> getActiveUsers() {
        String sql = "SELECT DISTINCT user_id, brand_name, chat_type FROM " + entityData.getTableName() +
                " WHERE summarized_at IS NULL AND user_id IS NOT NULL AND user_id <> 0" +
                " AND chat_type <> '" + ChatType.HELP.name() + "'";

        return client.preparedQuery(sql)
                .execute()
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transform(row -> new ActiveUserSession(
                        row.getLong("user_id"),
                        row.getString("brand_name"),
                        ChatType.valueOf(row.getString("chat_type"))
                ))
                .collect().asList();
    }

    public record ActiveUserSession(long userId, String brandName, ChatType chatType) {}
}
