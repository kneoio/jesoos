package com.semantyca.djinn.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.semantyca.djinn.dto.aiagent.VoiceDTO;
import com.semantyca.mixpla.model.cnst.TTSEngineType;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.user.SuperUser;
import io.kneo.officeframe.dto.GenreDTO;
import io.kneo.officeframe.dto.LabelDTO;
import io.kneo.officeframe.service.GenreService;
import io.kneo.officeframe.service.LabelService;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

@ApplicationScoped
public class RefService {
    private final LabelService labelService;
    private final GenreService genreService;
    private final ObjectMapper objectMapper;

    @Inject
    public RefService(LabelService labelService, GenreService genreService) {
        this.labelService = labelService;
        this.genreService = genreService;
        this.objectMapper = new ObjectMapper();
    }

    public Uni<List<VoiceDTO>> getAllVoices(TTSEngineType engineType) {
        return Uni.createFrom().item(() -> {
            String fileName = engineType.getValue() + "-voices.json";
            try (InputStream inputStream = getClass().getClassLoader().getResourceAsStream(fileName)) {
                if (inputStream == null) {
                    throw new RuntimeException(fileName + " file not found in resources");
                }
                return objectMapper.readValue(inputStream, new TypeReference<>() {});
            } catch (IOException e) {
                throw new RuntimeException("Error reading " + fileName, e);
            }
        });
    }

    public Uni<Integer> getAllGenresCount() {
        return genreService.getAllCount(SuperUser.build());
    }

    public Uni<List<GenreDTO>> getAllGenres(final int limit, final int offset) {
        return genreService.getAll(limit, offset,null, LanguageCode.en);
    }

    public Uni<List<LabelDTO>> getSoundFragmentLabels() {
        return labelService.getOfCategory("sound_fragment", LanguageCode.en);
    }
}
