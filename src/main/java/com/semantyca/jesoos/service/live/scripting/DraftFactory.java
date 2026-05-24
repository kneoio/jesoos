package com.semantyca.jesoos.service.live.scripting;

import com.semantyca.core.model.cnst.LanguageCode;
import com.semantyca.core.model.cnst.LanguageTag;
import com.semantyca.core.model.user.SuperUser;
import com.semantyca.core.util.WebHelper;
import com.semantyca.jesoos.external.PerplexityApiClient;
import com.semantyca.jesoos.external.WeatherApiClient;
import com.semantyca.jesoos.external.WorldNewsApiClient;
import com.semantyca.jesoos.dto.BrandListenerDTO;
import com.semantyca.jesoos.service.AiAgentService;
import com.semantyca.jesoos.service.DraftService;
import com.semantyca.jesoos.service.ListenerService;
import com.semantyca.jesoos.service.ProfileService;
import com.semantyca.jesoos.service.maintenance.ChatSummaryService;
import com.semantyca.jesoos.util.TimeContextUtil;
import com.semantyca.mixpla.model.Draft;
import com.semantyca.mixpla.model.Profile;
import com.semantyca.mixpla.model.aiagent.AiAgent;
import com.semantyca.mixpla.model.brand.AiOverriding;
import com.semantyca.mixpla.model.brand.ProfileOverriding;
import com.semantyca.mixpla.model.soundfragment.SoundFragment;
import com.semantyca.mixpla.model.stream.IStream;
import com.semantyca.mixpla.template.GroovyTemplateEngine;
import com.semantyca.officeframe.model.cnst.CountryCode;
import com.semantyca.officeframe.service.GenreService;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Collectors;

import static io.smallrye.mutiny.infrastructure.Infrastructure.getDefaultWorkerPool;

@ApplicationScoped
public class DraftFactory {
    private static final Logger LOGGER = LoggerFactory.getLogger(DraftFactory.class);

    private final GenreService genreService;
    private final ProfileService profileService;
    private final DraftService draftService;
    private final AiAgentService aiAgentService;
    private final WeatherApiClient weatherApiClient;
    private final WorldNewsApiClient worldNewsApiClient;
    private final PerplexityApiClient perplexityApiClient;
    private final ListenerService listenerService;
    private final ChatSummaryService chatSummaryService;
    private final Random random = new Random();
    private final GroovyTemplateEngine groovyEngine;

    @Inject
    public DraftFactory(GenreService genreService, ProfileService profileService, DraftService draftService,
                        AiAgentService aiAgentService, WeatherApiClient weatherApiClient,
                        WorldNewsApiClient worldNewsApiClient, PerplexityApiClient perplexityApiClient,
                        ListenerService listenerService, ChatSummaryService chatSummaryService) {
        this.genreService = genreService;
        this.profileService = profileService;
        this.draftService = draftService;
        this.aiAgentService = aiAgentService;
        this.weatherApiClient = weatherApiClient;
        this.worldNewsApiClient = worldNewsApiClient;
        this.perplexityApiClient = perplexityApiClient;
        this.listenerService = listenerService;
        this.chatSummaryService = chatSummaryService;
        this.groovyEngine = new GroovyTemplateEngine();
    }

    public Uni<String> createDraft(
            SoundFragment song,
            AiAgent agent,
            IStream stream,
            UUID draftId,
            LanguageTag selectedLanguage,  //always EN
            Map<String, Object> userVariables,
            String sharerName
    ) {
        Uni<AiAgent> copilotUni = agent.getCopilot() != null
                ? aiAgentService.getById(agent.getCopilot(), SuperUser.build())
                : Uni.createFrom().nullItem();

        Uni<List<String>> genresUni = song != null
                ? resolveGenreNames(song, selectedLanguage.toLanguageCode())
                : Uni.createFrom().item(List.of());

        return Uni.combine().all()
                .unis(
                        getDraftTemplate(draftId, stream.getSlugName()),  //the drafts always un ENG
                        profileService.getById(stream.getProfileId()),
                        genresUni,
                        copilotUni,
                        listenerService.getBrandListeners(stream.getSlugName(), 500, 0, SuperUser.build(), null),
                        chatSummaryService.getLatestBrandSummary(stream.getSlugName())
                )
                .asTuple()
                .emitOn(getDefaultWorkerPool())
                .map(tuple -> {
                    Draft template = tuple.getItem1();
                    Profile profile = tuple.getItem2();
                    List<String> genres = tuple.getItem3();
                    AiAgent copilot = tuple.getItem4();
                    List<BrandListenerDTO> listeners = tuple.getItem5();
                    String chatSummary = tuple.getItem6();

                    if (template != null) {
                        return buildFromTemplate(
                                template.getContent(),
                                song,
                                agent,
                                copilot,
                                stream,
                                profile,
                                genres,
                                listeners,
                                selectedLanguage,
                                userVariables,
                                chatSummary,
                                WebHelper.generateSlug(template.getTitle()),
                                sharerName
                        );
                    } else {
                        String msg = "No draft template found. Fallbacks are disabled.";
                        LOGGER.error(msg);
                        throw new IllegalStateException(msg);
                    }
                });
    }



    private Uni<Draft> getDraftTemplate(UUID id, String stationSlug) {
        if (id == null) {
            String errorMsg = String.format(
                    "Prompt configuration error: draftId is null for station='%s'. Check prompt configuration - all prompts must have an associated draft template.",
                    stationSlug
            );
            LOGGER.error(errorMsg);
            return Uni.createFrom().failure(new IllegalStateException(errorMsg));
        }
        return draftService.getById(id, SuperUser.build())
                .onFailure().transform(t -> {
                    String errorMsg = String.format(
                            "Draft template not found: draftId='%s', station='%s'. Error: %s",
                            id, stationSlug, t.getMessage()
                    );
                    LOGGER.error(errorMsg, t);
                    return new IllegalStateException(errorMsg, t);
                });
    }

    private String buildFromTemplate(
            String template,
            SoundFragment song,
            AiAgent agent,
            AiAgent copilot,
            IStream stream,
            Profile profile,
            List<String> genres,
            List<BrandListenerDTO> listeners,
            LanguageTag selectedLanguage,
            Map<String, Object> userVariables,
            String chatSummary,
            String draftSlug,
            String sharerName
    ) {
        CountryCode countryIso = stream.getCountry();
        Map<String, Object> data = new HashMap<>();

        if (userVariables != null && !userVariables.isEmpty()) {
            data.putAll(userVariables);
        }

        data.put("coPilotName", copilot.getName());
        data.put("coPilotVoiceId", copilot.getTtsSetting().getDj().getId());
        data.put("listeners", listeners);
        String brand = stream.getLocalizedName().get(selectedLanguage.toLanguageCode());
        if (brand == null) {
            brand = stream.getLocalizedName().values().iterator().next();
        }
        AiOverriding overriddenAiDj = stream.getAiOverriding();
        if (overriddenAiDj != null) {
            data.put("djName", overriddenAiDj.getName());
            data.put("djVoiceId", overriddenAiDj.getPrimaryVoice());
        } else {
            data.put("djName", agent.getName());
            data.put("djVoiceId", agent.getTtsSetting().getDj().getId());
        }
        ProfileOverriding overriddenProfile = stream.getProfileOverriding();
        if (overriddenProfile != null) {
            if (!overriddenProfile.getName().isEmpty()) {
                data.put("profileName", overriddenProfile.getName());
            } else {
                data.put("profileName", profile.getName());
            }
            if (!overriddenProfile.getDescription().trim().isEmpty()) {
                data.put("profileDescription", overriddenProfile.getDescription());
            } else {
                data.put("profileDescription", profile.getDescription());
            }
        } else {
            data.put("profileName", profile.getName());
            data.put("profileDescription", profile.getDescription());
        }
        data.put("stationBrand", brand);
        data.put("slugName", stream.getSlugName());
        data.put("country", stream.getCountry());
        data.put("language", selectedLanguage);
        data.put("random", random);
        data.put("perplexity", new PerplexitySearchHelper(perplexityApiClient));
        data.put("weather", new WeatherHelper(weatherApiClient, countryIso));
        data.put("news", new NewsHelper(worldNewsApiClient, countryIso, selectedLanguage.name()));
        data.put("timeContext", TimeContextUtil.getCurrentMomentDetailed(stream.getTimeZone()));
        data.put("chatSummary", chatSummary != null ? chatSummary : "");

        if (song != null) {
            data.put("songTitle", song.getTitle());
            data.put("songArtist", song.getArtist());
            data.put("songDescription", song.getDescription());
            data.put("songGenres", genres);
            data.put("songSharerName", sharerName != null ? sharerName : "");
        } else {
            data.put("songTitle", "");
            data.put("songArtist", "");
            data.put("songDescription", "");
            data.put("songGenres", List.of());
            data.put("songSharerName", "");
        }

        return groovyEngine.render(template, data, draftSlug).trim();
    }

    public Uni<Map<String, Object>> buildActionContext(
            SoundFragment song,
            IStream stream,
            List<String> contextVars,
            LanguageTag language
    ) {
        Uni<List<String>> genresUni = song != null
                ? resolveGenreNames(song, language.toLanguageCode())
                : Uni.createFrom().item(List.of());

        return genresUni.map(genres -> {
            Map<String, Object> ctx = new HashMap<>();
            ctx.put("songTitle", song != null && song.getTitle() != null ? song.getTitle() : "");
            ctx.put("songArtist", song != null && song.getArtist() != null ? song.getArtist() : "");
            ctx.put("description", song != null && song.getDescription() != null ? song.getDescription() : "");
            ctx.put("genre", String.join(", ", genres));
            ctx.put("country", stream.getCountry() != null ? stream.getCountry().toString() : "");
            String brand = stream.getLocalizedName().get(language.toLanguageCode());
            if (brand == null && !stream.getLocalizedName().isEmpty()) {
                brand = stream.getLocalizedName().values().iterator().next();
            }
            ctx.put("stationBrand", brand != null ? brand : "");
            LOGGER.infof("Action context resolved: %s", ctx);
            return ctx;
        });
    }

    private Uni<List<String>> resolveGenreNames(SoundFragment song, LanguageCode selectedLanguage) {
        List<UUID> genreIds = song.getGenres();
        if (genreIds == null || genreIds.isEmpty()) {
            LOGGER.warn("Song '{}' (ID: {}) has no genres assigned", song.getTitle(), song.getId());
            return Uni.createFrom().item(List.of());
        }

        List<Uni<String>> genreUnis = genreIds.stream()
                .map(genreId -> genreService.getById(genreId)
                        .map(genre -> genre.getLocalizedName().get(selectedLanguage)))
                .collect(Collectors.toList());

        return Uni.join().all(genreUnis).andFailFast();
    }

}
