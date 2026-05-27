package com.semantyca.jesoos.service.chat.tools;

import com.semantyca.jesoos.service.chat.llm.LlmTool;

import java.util.List;
import java.util.Map;

public class GetBrandCatalogSummary {

    public static LlmTool toTool() {
        return LlmTool.of(
                "get_brand_catalog_summary",
                "Get a full catalog summary for a radio station: total track count, all distinct artists with song counts, all genres with song counts, and all labels with song counts. Use this before searching to discover available genres and labels to include in keywords.",
                Map.of(
                        "brandName", Map.of(
                                "type", "string",
                                "description", "The radio station slug name (e.g., 'lumisonic'). Use the current station's slug from the context.")
                ),
                List.of("brandName")
        );
    }
}
