package com.semantyca.jesoos.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.semantyca.core.repository.AsyncRepository;
import com.semantyca.core.repository.rls.RLSRepository;
import com.semantyca.core.repository.table.EntityData;
import com.semantyca.jesoos.model.chat.ChatSummary;
import com.semantyca.jesoos.model.cnst.ChatType;
import com.semantyca.mixpla.repository.MixplaNameResolver;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.pgclient.PgPool;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static com.semantyca.mixpla.repository.MixplaNameResolver.CHAT_SUMMARY;


@ApplicationScoped
public class ChatSummaryRepository extends AsyncRepository {
    private static final Logger LOGGER = LoggerFactory.getLogger(ChatSummaryRepository.class);
    private static final EntityData entityData = MixplaNameResolver.create().getEntityNames(CHAT_SUMMARY);

    @Inject
    public ChatSummaryRepository(Pool client, ObjectMapper mapper, RLSRepository rlsRepository) {
        super(client, mapper, rlsRepository);
    }

    public Uni<UUID> save(ChatSummary summary) {
        String sql = "INSERT INTO " + entityData.getTableName() +
                " (id, brand_name, summary_type, user_id, chat_type, summary, message_count, period_start, period_end, created_at) " +
                "VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10) RETURNING id";

        UUID id = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();

        return client.preparedQuery(sql)
                .execute(Tuple.of(
                        id,
                        summary.getBrandName(),
                        summary.getSummaryType().name(),
                        summary.getUserId(),
                        summary.getChatType() != null ? summary.getChatType().name() : null,
                        summary.getSummary()
                ).addInteger(summary.getMessageCount())
                 .addLocalDateTime(summary.getPeriodStart())
                 .addLocalDateTime(summary.getPeriodEnd())
                 .addLocalDateTime(now))
                .onItem().transform(rows -> {
                    Row row = rows.iterator().next();
                    return row.getUUID("id");
                })
                .onFailure().invoke(throwable ->
                        LOGGER.error("Failed to save chat summary for brand {}", summary.getBrandName(), throwable)
                );
    }

    public Uni<Optional<ChatSummary>> getLatestBrandSummary(String brandName) {
        String sql = "SELECT * FROM " + entityData.getTableName() +
                " WHERE brand_name = $1 AND summary_type = 'BRAND' " +
                "ORDER BY created_at DESC LIMIT 3";

        return client.preparedQuery(sql)
                .execute(Tuple.of(brandName))
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transformToUni(this::from)
                .concatenate()
                .collect().first()
                .onItem().transform(Optional::ofNullable);
    }

    public Uni<Optional<ChatSummary>> getLatestUserSummary(long userId, String brandName, ChatType chatType) {
        String sql = "SELECT * FROM " + entityData.getTableName() +
                " WHERE user_id = $1 AND brand_name = $2 AND chat_type = $3 AND summary_type = 'USER' " +
                "ORDER BY created_at DESC LIMIT 1";

        return client.preparedQuery(sql)
                .execute(Tuple.of(userId, brandName, chatType.name()))
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transformToUni(this::from)
                .concatenate()
                .collect().first()
                .onItem().transform(Optional::ofNullable);
    }

    public Uni<ChatSummary> from(Row row) {
        ChatSummary entity = new ChatSummary();
        entity.setId(row.getUUID("id"));
        entity.setBrandName(row.getString("brand_name"));
        entity.setSummaryType(ChatSummary.SummaryType.valueOf(row.getString("summary_type")));
        Long userId = row.getLong("user_id");
        entity.setUserId(userId);
        String chatTypeStr = row.getString("chat_type");
        if (chatTypeStr != null) {
            entity.setChatType(ChatType.valueOf(chatTypeStr));
        }
        entity.setSummary(row.getString("summary"));
        entity.setMessageCount(row.getInteger("message_count"));
        entity.setPeriodStart(row.getLocalDateTime("period_start"));
        entity.setPeriodEnd(row.getLocalDateTime("period_end"));
        entity.setCreatedAt(row.getLocalDateTime("created_at"));
        return Uni.createFrom().item(entity);
    }
}
