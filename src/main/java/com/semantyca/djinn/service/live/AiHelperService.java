package com.semantyca.djinn.service.live;

import com.google.common.math.StatsAccumulator;
import com.semantyca.core.model.cnst.LanguageTag;
import com.semantyca.djinn.dto.AiDjStatsDTO;
import com.semantyca.djinn.dto.AvailableStationsAiDTO;
import com.semantyca.djinn.dto.BrandSoundFragmentAiDTO;
import com.semantyca.djinn.dto.BrandSoundFragmentDTO;
import com.semantyca.djinn.dto.RadioStationAiDTO;
import com.semantyca.djinn.dto.radiostation.AiOverridingDTO;
import com.semantyca.djinn.dto.radiostation.BrandDTO;
import com.semantyca.djinn.service.AiAgentService;
import com.semantyca.djinn.service.BrandService;
import com.semantyca.djinn.service.ScriptService;
import com.semantyca.djinn.service.soundfragment.SoundFragmentService;
import com.semantyca.mixpla.model.Scene;
import com.semantyca.mixpla.model.aiagent.AiAgent;
import com.semantyca.mixpla.model.aiagent.LanguagePreference;
import com.semantyca.mixpla.model.cnst.StreamStatus;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.user.SuperUser;
import io.kneo.officeframe.service.GenreService;
import io.kneo.officeframe.service.LabelService;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@ApplicationScoped
public class AiHelperService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AiHelperService.class);

    public record DjRequestInfo(LocalDateTime requestTime, String djName) {
    }

    private final Map<String, DjRequestInfo> aiDjStatsRequestTracker = new ConcurrentHashMap<>();
    private final Map<String, List<AiDjStatsDTO.StatusMessage>> aiDjMessagesTracker = new ConcurrentHashMap<>();

    private final BrandService brandService;
    private final AiAgentService aiAgentService;
    private final ScriptService scriptService;
    private final SoundFragmentService soundFragmentService;
    private final GenreService genreService;
    private final LabelService labelService;
    private final StatsAccumulator statsAccumulator = new StatsAccumulator();

    private static final int SCENE_START_SHIFT_MINUTES = 10;

    @Inject
    public AiHelperService(
            AiAgentService aiAgentService,
            ScriptService scriptService,
            BrandService brandService,
            SoundFragmentService soundFragmentService,
            GenreService genreService,
            LabelService labelService
    ) {
        this.aiAgentService = aiAgentService;
        this.scriptService = scriptService;
        this.brandService = brandService;
        this.soundFragmentService = soundFragmentService;
        this.genreService = genreService;
        this.labelService = labelService;
    }

    public Uni<AvailableStationsAiDTO> getAllStations(List<StreamStatus> statuses, String country, LanguageTag djLanguage, String query) {
        return brandService.getAllDTO(1000, 0, SuperUser.build(), country, query)
                .flatMap(stations -> {
                    if (stations == null || stations.isEmpty()) {
                        AvailableStationsAiDTO container = new AvailableStationsAiDTO();
                        container.setRadioStations(List.of());
                        return Uni.createFrom().item(container);
                    }

                    List<Uni<RadioStationAiDTO>> unis = stations.stream()
                            .map(dto -> {
                                if (statuses != null && !statuses.contains(dto.getStatus())) {
                                    return Uni.createFrom().<RadioStationAiDTO>nullItem();
                                }

                                if (djLanguage != null) {
                                    if (dto.getAiAgentId() == null) {
                                        return Uni.createFrom().<RadioStationAiDTO>nullItem();
                                    }
                                    return aiAgentService.getById(dto.getAiAgentId(), SuperUser.build(), LanguageCode.en)
                                            .map(agent -> {
                                                boolean supports = agent.getPreferredLang().stream()
                                                        .anyMatch(p -> p.getLanguageTag() == djLanguage);
                                                if (!supports) {
                                                    return null;
                                                }
                                                return toRadioStationAiDTO(dto, agent);
                                            })
                                            .onFailure().recoverWithItem(() -> null);
                                } else {
                                    if (dto.getAiAgentId() == null) {
                                        return Uni.createFrom().item(toRadioStationAiDTO(dto, null));
                                    }
                                    return aiAgentService.getById(dto.getAiAgentId(), SuperUser.build(), LanguageCode.en)
                                            .map(agent -> toRadioStationAiDTO(dto, agent))
                                            .onFailure().recoverWithItem(() -> toRadioStationAiDTO(dto, null));
                                }
                            })
                            .collect(Collectors.toList());

                    return (unis.isEmpty() ? Uni.createFrom().item(List.<RadioStationAiDTO>of()) : Uni.join().all(unis).andFailFast())
                            .map(list -> {
                                List<RadioStationAiDTO> stationsList = new ArrayList<>(list);
                                AvailableStationsAiDTO container = new AvailableStationsAiDTO();
                                container.setRadioStations(stationsList);
                                return container;
                            });
                });
    }

    public Uni<List<BrandSoundFragmentAiDTO>> searchBrandSoundFragmentsForAi(
            String brandName,
            String keyword,
            Integer limit,
            Integer offset
    ) {
        int actualLimit = (limit != null && limit > 0) ? limit : 50;
        int actualOffset = (offset != null && offset >= 0) ? offset : 0;

        return soundFragmentService.getBrandSoundFragmentsBySimilarity(brandName, keyword, actualLimit, actualOffset)
                .chain(brandFragments -> {
                    if (brandFragments == null || brandFragments.isEmpty()) {
                        return Uni.createFrom().item(Collections.<BrandSoundFragmentAiDTO>emptyList());
                    }

                    List<Uni<BrandSoundFragmentAiDTO>> aiDtoUnis = brandFragments.stream()
                            .map(this::mapToBrandSoundFragmentAiDTO)
                            .collect(Collectors.toList());

                    return Uni.join().all(aiDtoUnis).andFailFast();
                });
    }

    private RadioStationAiDTO toRadioStationAiDTO(BrandDTO brandDTO, AiAgent agent) {
        RadioStationAiDTO b = new RadioStationAiDTO();
        b.setLocalizedName(brandDTO.getLocalizedName());
        b.setSlugName(brandDTO.getSlugName());
        b.setCountry(brandDTO.getCountry());
        b.setHlsUrl(brandDTO.getHlsUrl());
        b.setMp3Url(brandDTO.getMp3Url());
        b.setMixplaUrl(brandDTO.getMixplaUrl());
        b.setTimeZone(brandDTO.getTimeZone());
        b.setDescription(brandDTO.getDescription());
        b.setBitRate(brandDTO.getBitRate());
        b.setStreamStatus(brandDTO.getStatus());
        if (agent != null) {
            b.setDjName(agent.getName());
            List<LanguageTag> langs = agent.getPreferredLang().stream()
                    .sorted(Comparator.comparingDouble(LanguagePreference::getWeight).reversed())
                    .map(LanguagePreference::getLanguageTag)
                    .collect(Collectors.toList());
            b.setAiAgentLang(langs);
        }

        if (brandDTO.isAiOverridingEnabled()){
                AiOverridingDTO aiOverriding = brandDTO.getAiOverriding();
                b.setOverriddenDjName(aiOverriding.getName());
                b.setAdditionalUserInstruction(aiOverriding.getPrompt());
        }
        return b;
    }

    private boolean isSceneActive(String stationSlug, ZoneId zone, Scene scene, NavigableSet<Scene> allScenes, LocalTime currentTime, int currentDayOfWeek) {

        List<Integer> weekdays = scene.getWeekdays();
        if (weekdays != null && !weekdays.isEmpty() && !weekdays.contains(currentDayOfWeek)) {
            return false;
        }

        if (scene.getStartTime() == null || scene.getStartTime().isEmpty()) {
            return true;
        }

        LocalTime sceneStart = scene.getStartTime().getFirst().minusMinutes(SCENE_START_SHIFT_MINUTES);
        LocalTime nextSceneStart = findNextSceneStartTime(stationSlug, currentDayOfWeek, scene, allScenes);

        boolean active;
        if (nextSceneStart != null && nextSceneStart.isBefore(sceneStart)) {
            active = !currentTime.isBefore(sceneStart) || currentTime.isBefore(nextSceneStart);
        } else if (nextSceneStart != null) {
            active = !currentTime.isBefore(sceneStart) || currentTime.isBefore(nextSceneStart);
        } else {
            active = !currentTime.isBefore(sceneStart);
        }

        return active;
    }



    private LocalTime findNextSceneStartTime(String stationSlug, int currentDayOfWeek, Scene currentScene, NavigableSet<Scene> scenes) {
        if (currentScene.getStartTime() == null || currentScene.getStartTime().isEmpty()) {
            return null;
        }
        LocalTime currentStart = currentScene.getStartTime().getFirst();
        List<LocalTime> sortedTimes = scenes.stream()
                .filter(s -> s.getWeekdays() == null || s.getWeekdays().isEmpty() || s.getWeekdays().contains(currentDayOfWeek))
                .filter(s -> s.getStartTime() != null && !s.getStartTime().isEmpty())
                .flatMap(s -> s.getStartTime().stream())
                .sorted()
                .distinct()
                .toList();
        for (LocalTime time : sortedTimes) {
            if (time.isAfter(currentStart)) {
                return time;
            }
        }
        // No distinct later start time today; signal end-of-day by returning null.
        return null;
    }

    public void addAiDj(String brand, String djName) {
        this.aiDjStatsRequestTracker.put(brand, new DjRequestInfo(LocalDateTime.now(), djName));
    }

    private Uni<BrandSoundFragmentAiDTO> mapToBrandSoundFragmentAiDTO(BrandSoundFragmentDTO brandFragment) {
        BrandSoundFragmentAiDTO aiDto = new BrandSoundFragmentAiDTO();
        aiDto.setId(brandFragment.getSoundFragmentDTO().getId());
        aiDto.setTitle(brandFragment.getSoundFragmentDTO().getTitle());
        aiDto.setArtist(brandFragment.getSoundFragmentDTO().getArtist());
        aiDto.setAlbum(brandFragment.getSoundFragmentDTO().getAlbum());
        aiDto.setDescription(brandFragment.getSoundFragmentDTO().getDescription());
        aiDto.setPlayedByBrandCount(brandFragment.getPlayedByBrandCount());
        aiDto.setLastTimePlayedByBrand(brandFragment.getLastTimePlayedByBrand());

        List<UUID> genreIds = brandFragment.getSoundFragmentDTO().getGenres();
        List<UUID> labelIds = brandFragment.getSoundFragmentDTO().getLabels();

        Uni<List<String>> genresUni = (genreIds != null && !genreIds.isEmpty())
                ? Uni.join().all(genreIds.stream()
                .map(genreId -> genreService.getById(genreId)
                        .map(genre -> genre.getLocalizedName().getOrDefault(LanguageCode.en, "Unknown"))
                        .onFailure().recoverWithItem("Unknown"))
                .collect(Collectors.toList())).andFailFast()
                : Uni.createFrom().item(Collections.<String>emptyList());

        Uni<List<String>> labelsUni = (labelIds != null && !labelIds.isEmpty())
                ? Uni.join().all(labelIds.stream()
                .map(labelId -> labelService.getById(labelId)
                        .map(label -> label.getLocalizedName().getOrDefault(LanguageCode.en, "Unknown"))
                        .onFailure().recoverWithItem("Unknown"))
                .collect(Collectors.toList())).andFailFast()
                : Uni.createFrom().item(Collections.<String>emptyList());

        return Uni.combine().all().unis(genresUni, labelsUni).asTuple()
                .map(tuple -> {
                    aiDto.setGenres(tuple.getItem1());
                    aiDto.setLabels(tuple.getItem2());
                    return aiDto;
                });
    }
}
