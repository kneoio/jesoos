package com.semantyca.jesoos.service.chat;

public enum ChatIntent {
    START_OTS,
    NORMAL_CHAT,
    /** Internal-only — always resolved to START_OTS or NORMAL_CHAT before leaving the router */
    UNKNOWN
}
