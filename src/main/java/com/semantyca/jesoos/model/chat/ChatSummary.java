package com.semantyca.jesoos.model.chat;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.semantyca.core.model.DataEntity;
import com.semantyca.jesoos.model.cnst.ChatType;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatSummary extends DataEntity<UUID> {
    private String brandName;
    private SummaryType summaryType;
    private Long userId;
    private ChatType chatType;
    private String summary;
    private int messageCount;
    private LocalDateTime periodStart;
    private LocalDateTime periodEnd;
    private LocalDateTime createdAt;

    @Deprecated
    public enum SummaryType {
        BRAND,
        USER
    }
}
