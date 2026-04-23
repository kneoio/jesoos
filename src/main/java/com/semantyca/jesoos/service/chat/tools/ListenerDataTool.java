package com.semantyca.jesoos.service.chat.tools;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;

import java.util.List;
import java.util.Map;

public class ListenerDataTool {

    public static Tool toTool() {
        Tool.InputSchema schema = Tool.InputSchema.builder()
                .properties(JsonValue.from(Map.of(
                        "action", Map.of(
                                "type", "string",
                                "enum", new String[]{"get", "set", "remove", "add_label", "remove_label"},
                                "description", "Action to perform: 'get' to retrieve all listener data, 'set' to store user data, 'remove' to delete a specific field, 'add_label' to assign a label, 'remove_label' to unassign a label"),
                        "field_name", Map.of(
                                "type", "string",
                                "description", "For 'set' or 'remove' actions: The name of the data field (e.g., 'telegram_name', 'city', 'country', 'favorite_genre', etc.). Not used for 'get', 'add_label', or 'remove_label' actions."),
                        "field_value", Map.of(
                                "type", "string",
                                "description", "For 'set' action only: The value to store for the field."),
                        "label_identifier", Map.of(
                                "type", "string",
                                "enum", new String[]{"artist"},
                                "description", "For 'add_label' or 'remove_label' actions: The label identifier to assign or remove. Available labels: 'artist'.")
                )))
                .required(List.of("action"))
                .build();

        return Tool.builder()
                .name("listener_data")
                .description("Manage listener data. Use 'get' to retrieve all listener information (profile + custom user_data + labels). The response includes has_artist_label (boolean) — use it for song upload eligibility. Use 'set' to store custom user data fields. Use 'remove' to delete specific fields. Use 'add_label' / 'remove_label' with label_identifier to manage labels (available: 'artist').")
                .inputSchema(schema)
                .build();
    }
}
