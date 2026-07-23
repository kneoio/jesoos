package com.semantyca.jesoos.service.chat.tools;

import com.semantyca.core.model.user.IUser;
import com.semantyca.core.service.UserService;
import com.semantyca.jesoos.service.ListenerService;
import com.semantyca.jesoos.service.chat.SunoImportService;
import com.semantyca.jesoos.service.chat.ToolNodeResult;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;

import java.util.Map;
import java.util.UUID;

public class SunoImportToolHandler extends BaseToolHandler {

    public static Uni<ToolNodeResult> execute(
            Map<String, Object> inputMap,
            SunoImportService sunoImportService,
            ListenerService listenerService, UserService userService,
            ListenerLabelCache labelCache, long userId) {
        String sunoUrl = ((String) inputMap.getOrDefault("suno_url", "")).trim();
        if (sunoUrl.isEmpty()) {
            return Uni.createFrom().item(ToolNodeResult.ok(
                    new JsonObject().put("ok", false).put("error", "suno_url is required").encode()));
        }
        return listenerService.getByUserId(userId)
                .chain(listener -> {
                    UUID artistLabelId = labelCache.get("artist");
                    if (listener == null || artistLabelId == null
                            || listener.getLabels() == null || !listener.getLabels().contains(artistLabelId)) {
                        return Uni.createFrom().item(ToolNodeResult.ok(
                                new JsonObject().put("ok", false)
                                        .put("error", "Artist label required. Call listener_data add_label with label_identifier=artist first.").encode()));
                    }
                    return userService.get(userId).chain(userOpt -> {
                        if (userOpt.isEmpty()) {
                            return Uni.createFrom().item(ToolNodeResult.ok(
                                    new JsonObject().put("ok", false).put("error", "User not found").encode()));
                        }
                        IUser user = userOpt.get();
                        return sunoImportService.downloadToTemp(sunoUrl, user)
                                .map(meta -> {
                                    JsonObject result = new JsonObject()
                                            .put("ok", true)
                                            .put("temp_filename", meta.tempFilename());
                                    if (meta.title() != null) result.put("title", meta.title());
                                    if (meta.artist() != null) result.put("artist", meta.artist());
                                    if (meta.handle() != null) result.put("handle", meta.handle());
                                    if (meta.genreTags() != null) result.put("genre_tags", meta.genreTags());
                                    if (meta.imageUrl() != null) result.put("image_url", meta.imageUrl());
                                    if (meta.durationSeconds() != null) result.put("duration_seconds", meta.durationSeconds());
                                    result.put("note", "Track downloaded from Suno with metadata. Do NOT ask the artist for "
                                            + "title/artist/genre from scratch when a value is present here. Map genre_tags onto the "
                                            + "station's available genres, then show the artist the resolved title / artist / genre and "
                                            + "ask them to confirm or correct it BEFORE calling upload_song. Only ask from scratch for "
                                            + "fields that came back empty.");
                                    return ToolNodeResult.ok(result.encode());
                                });
                    });
                })
                .onFailure().recoverWithItem(err -> ToolNodeResult.ok(
                        new JsonObject().put("ok", false).put("error", err.getMessage()).encode()));
    }
}
