package com.semantyca.jesoos.service.live;

import com.google.common.math.StatsAccumulator;
import com.semantyca.core.model.cnst.LanguageCode;
import com.semantyca.core.model.cnst.LanguageTag;
import com.semantyca.core.model.user.SuperUser;
import com.semantyca.jesoos.dto.AiDjStatsDTO;
import com.semantyca.jesoos.dto.AvailableStationsAiDTO;
import com.semantyca.jesoos.dto.BrandSoundFragmentAiDTO;
import com.semantyca.jesoos.dto.BrandSoundFragmentDTO;
import com.semantyca.jesoos.dto.RadioStationAiDTO;
import com.semantyca.jesoos.dto.radiostation.AiOverridingDTO;
import com.semantyca.jesoos.dto.radiostation.BrandDTO;
import com.semantyca.jesoos.service.AiAgentService;
import com.semantyca.jesoos.service.BrandService;
import com.semantyca.jesoos.service.soundfragment.SoundFragmentService;
import com.semantyca.mixpla.model.Scene;
import com.semantyca.mixpla.model.aiagent.AiAgent;
import com.semantyca.mixpla.model.aiagent.LanguagePreference;
import com.semantyca.mixpla.model.cnst.StreamStatus;
import com.semantyca.mixpla.model.filter.SoundFragmentFilter;
import com.semantyca.officeframe.dto.GenreDTO;
import com.semantyca.officeframe.dto.LabelDTO;
import com.semantyca.officeframe.service.GenreService;
import com.semantyca.officeframe.service.LabelService;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@ApplicationScoped
public class AiHelperService {
    private static final Logger LOGGER = Logger.getLogger(AiHelperService.class);

    public record DjRequestInfo(LocalDateTime requestTime, String djName) {
    }

    private final Map<String, DjRequestInfo> aiDjStatsRequestTracker = new ConcurrentHashMap<>();
    private final Map<String, List<AiDjStatsDTO.StatusMessage>> aiDjMessagesTracker = new ConcurrentHashMap<>();

    private final SoundFragmentService soundFragmentService;
    private final BrandService brandService;
    private final AiAgentService aiAgentService;
    private final GenreService genreService;
    private final LabelService labelService;
    private final StatsAccumulator statsAccumulator = new StatsAccumulator();

    private volatile List<GenreDTO> cachedGenres = Collections.emptyList();
    private volatile List<LabelDTO> cachedLabels = Collections.emptyList();
    private volatile String cachedMusicMetadata = "";

    private static final int SCENE_START_SHIFT_MINUTES = 10;

    @Inject
    public AiHelperService(
            SoundFragmentService soundFragmentService, AiAgentService aiAgentService,
            BrandService brandService,
            GenreService genreService,
            LabelService labelService
    ) {
        this.soundFragmentService = soundFragmentService;
        this.aiAgentService = aiAgentService;
        this.brandService = brandService;
        this.genreService = genreService;
        this.labelService = labelService;
    }

    @PostConstruct
    void init() {
        try {
            var result = Uni.combine().all().unis(
                    genreService.getAll(200, 0, LanguageCode.en),
                    labelService.getAll(200, 0, LanguageCode.en)
            ).asTuple().await().atMost(Duration.ofSeconds(30));

            this.cachedGenres = result.getItem1();
            this.cachedLabels = result.getItem2();
            this.cachedMusicMetadata = buildMusicMetadataSection(cachedGenres, cachedLabels);
            LOGGER.infof("Music metadata cached: %d genres, %d labels", cachedGenres.size(), cachedLabels.size());
        } catch (Exception e) {
            LOGGER.warn("Failed to preload music metadata at startup", e);
        }
    }

    private String buildMusicMetadataSection(List<GenreDTO> genres, List<LabelDTO> labels) {
        String genreNames = genres.stream()
                .map(g -> g.getLocalizedName().getOrDefault(LanguageCode.en, g.getIdentifier()))
                .filter(s -> s != null && !s.isBlank())
                .sorted()
                .collect(Collectors.joining(", "));
        String labelNames = labels.stream()
                .map(l -> l.getLocalizedName().getOrDefault(LanguageCode.en, l.getIdentifier()))
                .filter(s -> s != null && !s.isBlank())
                .sorted()
                .collect(Collectors.joining(", "));
        return "Genres: " + (genreNames.isBlank() ? "none" : genreNames) + "\n" +
                "Labels: " + (labelNames.isBlank() ? "none" : labelNames);
    }

    public String getCachedMusicMetadata() {
        return cachedMusicMetadata;
    }

    // TODO: jesoos should not know about stations directly — must contact aivox via RabbitMQ
    public Uni<AvailableStationsAiDTO> getAllStations(List<StreamStatus> statuses, String country, LanguageTag djLanguage, String query) {
        LOGGER.warn("getAllStations is a stub — station data should be fetched from aivox");
        AvailableStationsAiDTO container = new AvailableStationsAiDTO();
        container.setRadioStations(List.of());
        return Uni.createFrom().item(container);
    }

    public Uni<List<BrandSoundFragmentAiDTO>> searchBrandSoundFragmentsForAi(
            String brandName,
            String keyword,
            List<String> genreNames,
            List<String> labelNames,
            Integer limit,
            Integer offset
    ) {
        int actualLimit = (limit != null && limit > 0) ? Math.min(limit, 10) : 10;
        int actualOffset = (offset != null && offset >= 0) ? offset : 0;

        Uni<List<UUID>> genreIdsUni = resolveGenreNamesToIds(genreNames);
        Uni<List<UUID>> labelIdsUni = resolveLabelNamesToIds(labelNames);

        return Uni.combine().all().unis(genreIdsUni, labelIdsUni).asTuple()
                .chain(tuple -> {
                    SoundFragmentFilter filter = new SoundFragmentFilter();
                    if (!tuple.getItem1().isEmpty()) filter.setGenre(tuple.getItem1());
                    if (!tuple.getItem2().isEmpty()) filter.setLabels(tuple.getItem2());

                    return soundFragmentService.getBrandSoundFragmentsForAiWithFilter(brandName, keyword, filter, actualLimit, actualOffset)
                            .chain(brandFragments -> {
                                if (brandFragments == null || brandFragments.isEmpty()) {
                                    return Uni.createFrom().item(Collections.<BrandSoundFragmentAiDTO>emptyList());
                                }
                                List<Uni<BrandSoundFragmentAiDTO>> aiDtoUnis = brandFragments.stream()
                                        .map(this::mapToBrandSoundFragmentAiDTO)
                                        .collect(Collectors.toList());
                                return Uni.join().all(aiDtoUnis).andFailFast();
                            });
                });
    }

    private Uni<List<UUID>> resolveGenreNamesToIds(List<String> names) {
        if (names == null || names.isEmpty()) {
            return Uni.createFrom().item(Collections.emptyList());
        }
        return Uni.createFrom().item(
                names.stream()
                        .map(name -> cachedGenres.stream()
                                .filter(g -> name.equalsIgnoreCase(g.getLocalizedName().getOrDefault(LanguageCode.en, "")))
                                .map(GenreDTO::getId)
                                .findFirst().orElse(null))
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList())
        );
    }

    private Uni<List<UUID>> resolveLabelNamesToIds(List<String> names) {
        if (names == null || names.isEmpty()) {
            return Uni.createFrom().item(Collections.emptyList());
        }
        return Uni.createFrom().item(
                names.stream()
                        .map(name -> cachedLabels.stream()
                                .filter(l -> name.equalsIgnoreCase(l.getLocalizedName().getOrDefault(LanguageCode.en, "")))
                                .map(LabelDTO::getId)
                                .findFirst().orElse(null))
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList())
        );
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
