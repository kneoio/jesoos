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
                                "enum", new String[]{"get", "set", "remove"},
                                "description", "Action to perform: 'get' to retrieve all listener data, 'set' to store user data, 'remove' to delete a specific field"),
                        "field_name", Map.of(
                                "type", "string",
                                "description", "For 'set' or 'remove' actions: The name of the data field (e.g., 'telegram_name', 'city', 'country', 'favorite_genre', etc.). Not used for 'get' action."),
                        "field_value", Map.of(
                                "type", "string",
                                "description", "For 'set' action only: The value to store for the field. Not used for 'get' or 'remove' actions.")
                )))
                .required(List.of("action"))
                .build();

        return Tool.builder()
                .name("listener_data")
                .description("Manage listener data. Use 'get' to retrieve all listener information (profile + custom user_data). Use 'set' to store custom user data fields. Use 'remove' to delete specific fields. The 'get' action returns comprehensive data including email, names, nicknames, and all custom user_data fields.")
                .inputSchema(schema)
                .build();
    }
}
