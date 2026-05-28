package com.semantyca.jesoos.service.chat;

public enum ChatIntent {
    START_OTS,
    CREATE_AD,
    NORMAL_CHAT,
    /** Internal-only — always resolved before leaving the router */
    UNKNOWN
}
