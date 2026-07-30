package com.semantyca.jesoos.service.chat;

import com.semantyca.core.model.user.IUser;

import java.util.List;

public record ToolNodeResult(
        String payload,
        Long newUserId,
        IUser newUser,
        String sessionToken,
        String sessionUserName,
        boolean clearHistory,
        String wsMessage,
        List<String> labels,
        String listenerContext,
        String audiences
) {
    public static ToolNodeResult ok(String payload) {
        return new ToolNodeResult(payload, null, null, null, null, false, null, null, null, null);
    }

    public static ToolNodeResult withAuth(String payload, long userId, IUser user, String token, String username) {
        return new ToolNodeResult(payload, userId, user, token, username, false, null, null, null, null);
    }

    public static ToolNodeResult withAuth(String payload, long userId, IUser user, String token, String username,
                                         List<String> labels, String listenerContext, String audiences) {
        return new ToolNodeResult(payload, userId, user, token, username, false, null,
                labels, listenerContext, audiences);
    }

    public static ToolNodeResult logoff(String payload, String wsMessage) {
        return new ToolNodeResult(payload, 0L, null, null, null, true, wsMessage, null, null, null);
    }

    public static ToolNodeResult withWsMessage(String payload, String wsMessage) {
        return new ToolNodeResult(payload, null, null, null, null, false, wsMessage, null, null, null);
    }
}
