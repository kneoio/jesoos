package com.semantyca.jesoos.service.live;

import com.google.common.math.StatsAccumulator;
import com.semantyca.core.model.cnst.LanguageCode;
import com.semantyca.jesoos.dto.BrandSoundFragmentAiDTO;
import com.semantyca.jesoos.dto.BrandSoundFragmentDTO;
import com.semantyca.jesoos.dto.SoundFragmentDTO;
import com.semantyca.jesoos.service.AiAgentService;
import com.semantyca.jesoos.service.BrandService;
import com.semantyca.jesoos.service.soundfragment.SoundFragmentService;
import com.semantyca.mixpla.model.filter.SoundFragmentFilter;
import com.semantyca.officeframe.dto.GenreDTO;
import com.semantyca.officeframe.dto.LabelDTO;
import com.semantyca.officeframe.service.GenreService;
import com.semantyca.officeframe.service.LabelService;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.Getter;
import org.jboss.logging.Logger;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@ApplicationScoped
public class AiHelperService {
    private static final Logger LOGGER = Logger.getLogger(AiHelperService.class);

    public record DjRequestInfo(LocalDateTime requestTime, String djName) {
    }

    private final Map<String, DjRequestInfo> aiDjStatsRequestTracker = new ConcurrentHashMap<>();

    private final SoundFragmentService soundFragmentService;
    private final BrandService brandService;
    private final AiAgentService aiAgentService;
    private final GenreService genreService;
    private final LabelService labelService;
    private final StatsAccumulator statsAccumulator = new StatsAccumulator();

    private volatile List<GenreDTO> cachedGenres = Collections.emptyList();
    private volatile List<LabelDTO> cachedLabels = Collections.emptyList();
    @Getter
    private volatile String cachedMusicMetadata = "";


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
        Uni.combine().all().unis(
                genreService.getAll(200, 0, LanguageCode.en),
                labelService.getAll(200, 0, LanguageCode.en)
        ).asTuple().subscribe().with(
                result -> {
                    this.cachedGenres = result.getItem1();
                    this.cachedLabels = result.getItem2();
                    this.cachedMusicMetadata = buildMusicMetadataSection(cachedGenres, cachedLabels);
                    LOGGER.infof("Music metadata cached: %d genres, %d labels", cachedGenres.size(), cachedLabels.size());
                },
                err -> LOGGER.warn("Failed to preload music metadata at startup", err)
        );
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

    // Owner-scoped sibling of searchBrandSoundFragmentsForAi — used by OTS chat over an owner-scoped
    // one-time stream. Same AiDTO shape so the chat search tool handler is scope-agnostic.
    public Uni<List<BrandSoundFragmentAiDTO>> searchOwnerSoundFragmentsForAi(
            long userId,
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

                    return soundFragmentService.getOwnerSoundFragmentsForAiWithFilter(userId, keyword, filter, actualLimit, actualOffset)
                            .chain(dtos -> {
                                if (dtos == null || dtos.isEmpty()) {
                                    return Uni.createFrom().item(Collections.<BrandSoundFragmentAiDTO>emptyList());
                                }
                                List<Uni<BrandSoundFragmentAiDTO>> aiDtoUnis = dtos.stream()
                                        .map(this::mapDtoToBrandSoundFragmentAiDTO)
                                        .collect(Collectors.toList());
                                return Uni.join().all(aiDtoUnis).andFailFast();
                            });
                });
    }

    public Uni<JsonObject> getBrandCatalogSummaryForAi(String brandName) {
        return soundFragmentService.getBrandCatalogSummary(brandName);
    }

    public Uni<List<UUID>> resolveGenreNamesToIds(List<String> names) {
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

    // Owner-scoped variant of mapToBrandSoundFragmentAiDTO: builds the same AiDTO from a plain
    // SoundFragmentDTO (no brand association, so no brand play stats).
    private Uni<BrandSoundFragmentAiDTO> mapDtoToBrandSoundFragmentAiDTO(SoundFragmentDTO dto) {
        BrandSoundFragmentAiDTO aiDto = new BrandSoundFragmentAiDTO();
        aiDto.setId(dto.getId());
        aiDto.setTitle(dto.getTitle());
        aiDto.setArtist(dto.getArtist());
        aiDto.setAlbum(dto.getAlbum());
        aiDto.setDescription(dto.getDescription());

        List<UUID> genreIds = dto.getGenres();
        List<UUID> labelIds = dto.getLabels();

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
