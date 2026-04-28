package com.semantyca.jesoos.service.chat.ots;

public record OtsResult(Action action, String mixplaUrl, String question) {
    public enum Action { ASK_QUESTION, STREAM_STARTED }
}
