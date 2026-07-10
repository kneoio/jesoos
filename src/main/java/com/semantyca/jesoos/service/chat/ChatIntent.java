package com.semantyca.jesoos.service.chat;

public enum ChatIntent {
    CREATE_AD,
    NORMAL_CHAT,
    /** Internal-only — always resolved before leaving the router */
    UNKNOWN
}
