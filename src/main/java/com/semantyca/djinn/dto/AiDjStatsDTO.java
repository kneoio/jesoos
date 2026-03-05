package com.semantyca.djinn.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AiDjStatsDTO {
    private LocalDateTime lastRequestTime;
    private String djName;
    private List<StatusMessage> messages;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StatusMessage {
        private MessageType type;
        private String message;
    }

    public enum MessageType {
        INFO,
        WARNING,
        ERROR
    }
}
