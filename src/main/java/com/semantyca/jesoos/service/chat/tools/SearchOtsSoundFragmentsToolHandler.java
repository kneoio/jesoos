package com.semantyca.jesoos.service.chat.tools;

import com.semantyca.jesoos.service.live.AiHelperService;
import com.semantyca.jesoos.service.live.OneTimeStreamPool;
import com.semantyca.mixpla.model.brand.Brand;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.Collections;
import java.util.Map;

/**
 * OTS-scoped counterpart of {@link SearchBrandSoundFragmentsToolHandler}. Resolves the live OTS from
 * {@link OneTimeStreamPool} and searches its {@code SongSourceScope}: the master brand's catalog when
 * the OTS is brand-scoped, or the owner's (author's) catalog when owner-scoped (synthetic brand,
 * {@code brand.getId() == null}). Same result shape as the brand search so the DJ prompt is identical.
 */
public class SearchOtsSoundFragmentsToolHandler extends BaseToolHandler {

    public static Uni<com.semantyca.jesoos.service.chat.ToolNodeResult> execute(
            Map<String, Object> inputMap, String otsSlug,
            OneTimeStreamPool oneTimeStreamPool, AiHelperService aiHelperService) {
        String keyword = inputMap.containsKey("keyword") ? (String) inputMap.get("keyword") : "";
        Integer limit = null;
        Integer offset = null;
        try { if (inputMap.containsKey("limit")) limit = ((Number) inputMap.get("limit")).intValue(); } catch (Exception ignored) {}
        try { if (inputMap.containsKey("offset")) offset = ((Number) inputMap.get("offset")).intValue(); } catch (Exception ignored) {}
        final Integer fLimit = limit;
        final Integer fOffset = offset;
        final String kw = keyword;

        return oneTimeStreamPool.get(otsSlug)
                .chain(stream -> {
                    if (stream == null) {
                        return Uni.createFrom().item(com.semantyca.jesoos.service.chat.ToolNodeResult.ok(
                                new JsonObject().put("ok", false).put("error", "Event stream offline").encode()));
                    }
                    Brand brand = stream.getBrand();
                    Uni<java.util.List<com.semantyca.jesoos.dto.BrandSoundFragmentAiDTO>> searchUni =
                            (brand != null && brand.getId() != null)
                                    ? aiHelperService.searchBrandSoundFragmentsForAi(brand.getSlugName(), kw,
                                            Collections.emptyList(), Collections.emptyList(), fLimit, fOffset)
                                    : aiHelperService.searchOwnerSoundFragmentsForAi(brand.getOwner().getUserId(), kw,
                                            Collections.emptyList(), Collections.emptyList(), fLimit, fOffset);
                    return searchUni.map(list -> {
                        JsonArray items = new JsonArray();
                        list.forEach(f -> items.add(new JsonObject()
                                .put("id", String.valueOf(f.getId())).put("title", f.getTitle())
                                .put("artist", f.getArtist()).put("genres", f.getGenres())
                                .put("album", f.getAlbum()).put("description", f.getDescription())));
                        return com.semantyca.jesoos.service.chat.ToolNodeResult.ok(items.encode());
                    });
                })
                .onFailure().recoverWithItem(err -> com.semantyca.jesoos.service.chat.ToolNodeResult.ok(
                        new JsonObject().put("ok", false).put("error", err.getMessage()).encode()));
    }
}
