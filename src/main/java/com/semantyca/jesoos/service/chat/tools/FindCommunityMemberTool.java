package com.semantyca.jesoos.service.chat.tools;

import com.semantyca.jesoos.service.chat.llm.LlmTool;

import java.util.List;
import java.util.Map;

public class FindCommunityMemberTool {

    public static LlmTool toTool() {
        return LlmTool.of(
                "find_community_member",
                "Find other listeners of this station who share the same company, community group, or name. Returns only first name and the matched field — no email, no contacts, no sensitive info. Use after storing company or community_group, or when user asks about a specific person.",
                Map.of(
                        "field_name", Map.of(
                                "type", "string",
                                "enum", new String[]{"company", "community_group", "preferred_name", "interests"},
                                "description", "Which field to search by: 'company' for same workplace, 'community_group' for same Telegram/Discord group, 'preferred_name' for a specific person, 'interests' for shared hobby/activity (automatically filtered by same city if available)"),
                        "field_value", Map.of(
                                "type", "string",
                                "description", "The value to search for (e.g. company name, group name, or person's name)")
                ),
                List.of("field_name", "field_value")
        );
    }
}
