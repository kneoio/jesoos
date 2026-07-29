package com.semantyca.jesoos.service.chat.tools;

import com.semantyca.core.model.user.IUser;
import com.semantyca.core.service.UserService;
import com.semantyca.jesoos.config.JesoosConfig;
import com.semantyca.jesoos.dto.SoundFragmentDTO;
import com.semantyca.jesoos.outbound.SpectraApiClient;
import com.semantyca.jesoos.service.AiAgentService;
import com.semantyca.jesoos.service.BrandService;
import com.semantyca.jesoos.service.ListenerService;
import com.semantyca.jesoos.service.live.AiHelperService;
import com.semantyca.jesoos.service.live.BrandPool;
import com.semantyca.jesoos.service.live.SongEmitter;
import com.semantyca.jesoos.service.soundfragment.SharedSoundFragmentService;
import com.semantyca.jesoos.service.soundfragment.SoundFragmentService;
import com.semantyca.mixpla.model.cnst.PlaylistItemType;
import com.semantyca.mixpla.model.cnst.SourceType;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class UploadSongToolHandler extends BaseToolHandler {

    public static Uni<com.semantyca.jesoos.service.chat.ToolNodeResult> execute(
            Map<String, Object> inputMap,
            ListenerService listenerService, UserService userService,
            SoundFragmentService soundFragmentService, SharedSoundFragmentService sharedSoundFragmentService,
            AiHelperService aiHelperService,
            BrandPool brandPool, SongEmitter songEmitter, AiAgentService aiAgentService,
            ListenerLabelCache labelCache, BrandService brandService,
            SpectraApiClient spectraClient, JesoosConfig config,
            String brandName, long userId) {
        UploadSongToolHandler h = new UploadSongToolHandler();
        String tempFilename = (String) inputMap.getOrDefault("temp_filename", "");
        String title = (String) inputMap.getOrDefault("title", "");
        String artist = (String) inputMap.getOrDefault("artist", "");
        String description = (String) inputMap.getOrDefault("description", "");
        String introText = (String) inputMap.getOrDefault("intro_text", "");
        List<String> genreNames = new ArrayList<>();
        if (inputMap.containsKey("genre_names") && inputMap.get("genre_names") instanceof List<?> list)
            list.forEach(g -> genreNames.add(g.toString()));
        if (tempFilename.isEmpty() || title.isEmpty() || artist.isEmpty()) {
            return Uni.createFrom().item(com.semantyca.jesoos.service.chat.ToolNodeResult.ok(
                    new JsonObject().put("ok", false).put("error", "temp_filename, title and artist are required").encode()));
        }
        return listenerService.getByUserId(userId)
                .chain(listener -> {
                    if (listener == null) return Uni.createFrom().item(com.semantyca.jesoos.service.chat.ToolNodeResult.ok(
                            new JsonObject().put("ok", false).put("error", "Listener not found").encode()));
                    UUID artistLabelId = labelCache.get("artist");
                    if (artistLabelId == null || listener.getLabels() == null || !listener.getLabels().contains(artistLabelId)) {
                        return Uni.createFrom().item(com.semantyca.jesoos.service.chat.ToolNodeResult.ok(
                                new JsonObject().put("ok", false).put("error", "Artist label required. Call listener_data add_label with label_identifier=artist first.").encode()));
                    }
                    return userService.get(userId).chain(userOpt -> {
                        if (userOpt.isEmpty()) return Uni.createFrom().item(com.semantyca.jesoos.service.chat.ToolNodeResult.ok(
                                new JsonObject().put("ok", false).put("error", "User not found").encode()));
                        IUser user = userOpt.get();
                        java.nio.file.Path uploaded = AssessTrackToolHandler.resolveTempFile(config, user.getLogin(), tempFilename);
                        if (!java.nio.file.Files.isRegularFile(uploaded)) return Uni.createFrom().item(com.semantyca.jesoos.service.chat.ToolNodeResult.ok(
                                new JsonObject().put("ok", false).put("error", "Uploaded file not found: " + tempFilename).encode()));
                        // Hard gate: never persist a non-music file (speech / spoken word). spectra returns is_music.
                        return spectraClient.assess(uploaded).chain(analysis -> {
                            if (!Boolean.TRUE.equals(analysis.getBoolean("is_music"))) return Uni.createFrom().item(com.semantyca.jesoos.service.chat.ToolNodeResult.ok(
                                    new JsonObject().put("ok", false).put("rejected", "not_music")
                                            .put("error", "This file is not music (speech / spoken word); only songs can be added to the catalog.").encode()));
                            return aiHelperService.resolveGenreNamesToIds(genreNames)
                                .chain(genreIds -> {
                                    SoundFragmentDTO dto = new SoundFragmentDTO();
                                    dto.setTitle(title); dto.setArtist(artist); dto.setDescription(description.isBlank() ? null : description);
                                    dto.setGenres(genreIds.isEmpty() ? List.of() : genreIds);
                                    dto.setSource(SourceType.CONTRIBUTION);
                                    // No LifecycleStatus/ApprovalStatus meaning left on the fragment itself - visibility and
                                    // approval now live entirely on the SharedSoundFragment PENDING share created below.
                                    // Placeholder value matches datanest's createFromBulkUpload (see the
                                    // knowledge bundle workflows/song-submission.md).
                                    dto.setStatus(1);
                                    dto.setNewlyUploaded(List.of(tempFilename));
                                    dto.setType(PlaylistItemType.SONG);
                                    // No RLS grant and no representedInBrands here - the author already gets RLS
                                    // automatically on insert, and brand visibility only happens once the target
                                    // station accepts the PENDING share (see shareContribution below).
                                    return brandPool.get(brandName)
                                            .chain(stream -> {
                                                Uni<UUID> brandIdUni = stream != null
                                                        ? Uni.createFrom().item(stream.getBrandId())
                                                        : brandService.getBySlugName(brandName).map(brand -> brand != null ? brand.getId() : null);
                                                return brandIdUni.chain(brandId ->
                                                        soundFragmentService.upsert("new", dto, user, com.semantyca.core.model.cnst.LanguageCode.en)
                                                                .chain(saved -> {
                                                                    if (brandId == null) {
                                                                        return Uni.createFrom().item(saved);
                                                                    }
                                                                    return sharedSoundFragmentService.shareContribution(
                                                                                    saved.getId(), brandId, userId, artist, user.getEmail())
                                                                            // Tag as "new" so it is floated to the front once the
                                                                            // owner accepts the share (approval still required).
                                                                            .chain(() -> sharedSoundFragmentService.addPriorityLabel(saved.getId()))
                                                                            .replaceWith(saved);
                                                                }));
                                            })
                                            .map(saved -> com.semantyca.jesoos.service.chat.ToolNodeResult.ok(
                                                    new JsonObject().put("ok", true).put("song_id", saved.getId().toString())
                                                            .put("title", title).put("queued", false).encode()));
                                });
                        });
                    });
                })
                .onFailure().recoverWithItem(err -> com.semantyca.jesoos.service.chat.ToolNodeResult.ok(
                        new JsonObject().put("ok", false).put("error", err.getMessage()).encode()));
    }
}
