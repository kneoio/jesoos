package com.semantyca.jesoos.service.chat;

import org.jboss.logging.Logger;

/**
 * Dedicated logger for all public chat communication events.
 * Configure level independently via:
 *   quarkus.log.category."jesoos.chat".level=DEBUG
 */
public final class ChatLogger {

    private static final Logger LOG = Logger.getLogger("jesoos.chat");

    private ChatLogger() {}

    public static void request(String slug, long userId, String email, boolean isAuthenticated, String stationStatus) {
        LOG.infof("[request] slug=%s userId=%d email=%s isAuthenticated=%b status=%s",
                slug, userId, email != null ? email : "null", isAuthenticated, stationStatus);
    }

    public static void tools(boolean isAuthenticated, int historySize, String toolNames) {
        LOG.infof("[tools] isAuthenticated=%b historySize=%d tools=[%s]",
                isAuthenticated, historySize, toolNames);
    }

    public static void firstCall(String toolName) {
        LOG.infof("[first-call] tool=%s", toolName);
    }

    public static void firstCallNoTool() {
        LOG.infof("[first-call] no tool — streaming text response");
    }

    public static void followUp(String toolName) {
        LOG.infof("[follow-up] tool=%s", toolName);
    }

    public static void followUpNoTool() {
        LOG.infof("[follow-up] no tool — streaming text response");
    }

    public static void toolResult(String toolName, boolean ok) {
        LOG.infof("[tool-result] tool=%s ok=%b", toolName, ok);
    }

    public static void error(String context, Throwable err) {
        LOG.errorf(err, "[error] context=%s msg=%s", context, err.getMessage());
    }
}
