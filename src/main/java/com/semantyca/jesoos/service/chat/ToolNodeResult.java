package com.semantyca.jesoos.service.chat;

import com.semantyca.core.model.user.IUser;

public record ToolNodeResult(
        String payload,
        Long newUserId,
        IUser newUser,
        String sessionToken,
        String sessionUserName,
        boolean clearHistory,
        String wsMessage
) {
    public static ToolNodeResult ok(String payload) {
        return new ToolNodeResult(payload, null, null, null, null, false, null);
    }

    public static ToolNodeResult withAuth(String payload, long userId, IUser user, String token, String username) {
        return new ToolNodeResult(payload, userId, user, token, username, false, null);
    }

    public static ToolNodeResult logoff(String payload, String wsMessage) {
        return new ToolNodeResult(payload, 0L, null, null, null, true, wsMessage);
    }

    public static ToolNodeResult withWsMessage(String payload, String wsMessage) {
        return new ToolNodeResult(payload, null, null, null, null, false, wsMessage);
    }
}
