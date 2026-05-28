package com.semantyca.jesoos.service.chat.ad;

import java.util.UUID;

public record AdResult(Action action, UUID fragmentId, String question) {
    public enum Action {
        ASK_QUESTION,
        AD_CREATED
    }
}
