package com.semantyca.jesoos.service.chat.tools;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;

import java.util.List;
import java.util.Map;

public class ManageEventsTool {

    public static Tool toTool() {
        Tool.InputSchema schema = Tool.InputSchema.builder()
                .properties(JsonValue.from(Map.of(
                        "action", Map.of(
                                "type", "string",
                                "enum", new String[]{"list", "upsert"},
                                "description", "'list' to read upcoming events for this station, 'upsert' to add or update an event"),
                        "id", Map.of(
                                "type", "string",
                                "description", "Event UUID — provide to update an existing event, omit to create new"),
                        "description", Map.of(
                                "type", "string",
                                "description", "Human-readable event description, e.g. 'Maria birthday May 15', 'New Year celebration'"),
                        "type", Map.of(
                                "type", "string",
                                "enum", new String[]{"ADVERTISEMENT", "REMINDER", "BIRTHDAY", "NATIONAL_HOLIDAY", "ANNIVERSARY", "SPECIAL"},
                                "description", "Event type"),
                        "priority", Map.of(
                                "type", "string",
                                "enum", new String[]{"LOW", "MEDIUM", "HIGH", "CRITICAL"},
                                "description", "Event priority")
                )))
                .required(List.of("action"))
                .build();

        return Tool.builder()
                .name("manage_events")
                .description("Read station events (national holidays, birthdays, special moments) and add new ones that listeners share. Use 'list' to see what's scheduled, 'upsert' to save a new event or update an existing one. Events feed into on-air DJ intros automatically.")
                .inputSchema(schema)
                .build();
    }
}
