package com.semantyca.jesoos.service.chat.tools.ad;

import com.semantyca.jesoos.service.chat.llm.LlmTool;

import java.util.List;
import java.util.Map;

public class CreateAdTool {

    public static LlmTool toTool() {
        return LlmTool.of(
                "create_ad",
                "Create a user advertisement that will be recorded as audio and broadcast on the radio station",
                Map.of(
                        "brandSlugName", Map.of(
                                "type", "string",
                                "description", "Slug name of the radio station brand")
                ),
                List.of("brandSlugName")
        );
    }
}
