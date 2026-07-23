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
import com.semantyca.jesoos.service.chat.tools.ListenerLabelCache;
import com.semantyca.jesoos.service.maintenance.ChatSummaryService;
import com.semantyca.jesoos.util.AiHelperUtils;
import com.semantyca.jesoos.util.TimeContextUtil;
import com.semantyca.mixpla.model.Draft;
import com.semantyca.mixpla.model.Profile;
import com.semantyca.mixpla.model.aiagent.AiAgent;
import com.semantyca.mixpla.model.brand.AiOverriding;
import com.semantyca.mixpla.model.brand.ProfileOverriding;
import com.semantyca.mixpla.model.soundfragment.SoundFragment;
import com.semantyca.jesoos.model.stream.ILiveStream;
import com.semantyca.mixpla.template.GroovyTemplateEngine;
import com.semantyca.officeframe.model.cnst.CountryCode;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Collectors;

import static io.smallrye.mutiny.infrastructure.Infrastructure.getDefaultWorkerPool;

@ApplicationScoped
public class DraftFactory {
    private static final Logger LOGGER = Logger.getLogger(DraftFactory.class);

    private final ProfileService profileService;
    private final DraftService draftService;
    private final AiAgentService aiAgentService;
    private final WeatherApiClient weatherApiClient;
    private final WorldNewsApiClient worldNewsApiClient;
    private final PerplexityApiClient perplexityApiClient;
    private final ListenerService listenerService;
    private final ListenerLabelCache listenerLabelCache;
    private final ChatSummaryService chatSummaryService;
    private final io.vertx.mutiny.sqlclient.Pool dbClient;
    private final Random random = new Random();
    private final GroovyTemplateEngine groovyEngine;

    @Inject
    public DraftFactory(ProfileService profileService,
                        DraftService draftService, AiAgentService aiAgentService,
                        WeatherApiClient weatherApiClient, WorldNewsApiClient worldNewsApiClient,
                        PerplexityApiClient perplexityApiClient,
                        ListenerService listenerService, ListenerLabelCache listenerLabelCache,
                        ChatSummaryService chatSummaryService,
                        io.vertx.mutiny.sqlclient.Pool dbClient) {
        this.profileService = profileService;
        this.draftService = draftService;
        this.aiAgentService = aiAgentService;
        this.weatherApiClient = weatherApiClient;
        this.worldNewsApiClient = worldNewsApiClient;
        this.perplexityApiClient = perplexityApiClient;
        this.listenerService = listenerService;
        this.listenerLabelCache = listenerLabelCache;
        this.chatSummaryService = chatSummaryService;
        this.dbClient = dbClient;
        this.groovyEngine = new GroovyTemplateEngine();
    }

    public record DraftResult(String text, UUID selectedAdId, String selectedAdTitle, String selectedAdSlugName) {
    }

    public Uni<DraftResult> createDraft(
            SoundFragment song,
            SoundFragment previousSong,
            AiAgent agent,
            ILiveStream stream,
            UUID draftId,
            Map<String, Object> userVariables,
            String sharerName
    ) {
        LanguageTag selectedLanguage = AiHelperUtils.selectLanguageByWeight(agent);

        Uni<AiAgent> copilotUni = agent.getCopilot() != null
                ? aiAgentService.getById(agent.getCopilot())
                : Uni.createFrom().nullItem();

        Uni<List<String>> genresUni = song != null
                ? resolveGenreNames(song, selectedLanguage.toLanguageCode())
                : Uni.createFrom().item(List.of());

        Uni<List<String>> labelsUni = song != null
                ? resolveLabels(song, selectedLanguage.toLanguageCode())
                : Uni.createFrom().item(List.of());

        return Uni.combine().all()
                .unis(
                        getDraftTemplate(draftId, stream.getSlugName()),  //the drafts always un ENG
                        stream.getProfileId() != null ? profileService.getById(stream.getProfileId()) : Uni.createFrom().nullItem(),
                        genresUni,
                        copilotUni,
                        listenerService.getBrandListeners(stream.getSlugName(), 500, 0, SuperUser.build(), null),
                        chatSummaryService.getLatestBrandSummary(stream.getSlugName())
                )
                .asTuple()
                .emitOn(getDefaultWorkerPool())
                .chain(tuple -> labelsUni.emitOn(getDefaultWorkerPool()).map(labels -> {
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
                                previousSong,
                                agent,
                                copilot,
                                stream,
                                profile,
                                genres,
                                labels,
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
                }));

    }


    public Uni<String> renderCode(
            String templateCode,
            SoundFragment song,
            AiAgent agent,
            ILiveStream stream,
            String sharerName
    ) {
        LanguageTag selectedLanguage = com.semantyca.jesoos.util.AiHelperUtils.selectLanguageByWeight(agent);

        Uni<AiAgent> copilotUni = agent.getCopilot() != null
                ? aiAgentService.getById(agent.getCopilot())
                : Uni.createFrom().nullItem();
        Uni<List<String>> genresUni = resolveGenreNames(song, selectedLanguage.toLanguageCode());
        Uni<List<String>> labelsUni = resolveLabels(song, selectedLanguage.toLanguageCode());

        return Uni.combine().all()
                .unis(
                        stream.getProfileId() != null ? profileService.getById(stream.getProfileId()) : Uni.createFrom().nullItem(),
                        genresUni,
                        copilotUni,
                        listenerService.getBrandListeners(stream.getSlugName(), 500, 0, SuperUser.build(), null),
                        chatSummaryService.getLatestBrandSummary(stream.getSlugName())
                )
                .asTuple()
                .emitOn(getDefaultWorkerPool())
                .chain(tuple -> labelsUni.emitOn(getDefaultWorkerPool()).map(labels -> {
                    Profile profile = tuple.getItem1();
                    List<String> genres = tuple.getItem2();
                    AiAgent copilot = tuple.getItem3();
                    List<BrandListenerDTO> listeners = tuple.getItem4();
                    String chatSummary = tuple.getItem5();
                    return buildFromTemplate(
                            templateCode, song, null, agent, copilot, stream, profile,
                            genres, labels, listeners, selectedLanguage, null, chatSummary,
                            "debug-draft", sharerName
                    ).text();
                }));
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

    private DraftResult buildFromTemplate(
            String template,
            SoundFragment song,
            SoundFragment previousSong,
            AiAgent agent,
            AiAgent copilot,
            ILiveStream stream,
            Profile profile,
            List<String> genres,
            List<String> labels,
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
        data.put("opt", new DraftLineHelper(userVariables, random));

        data.put("coPilotName", copilot.getName());
        data.put("coPilotVoiceId", copilot.getTtsSetting().getDj().getId());
        data.put("listeners", resolveListenerNames(listeners, selectedLanguage.toLanguageCode()));
        data.put("community", new ListenersHelper(listeners, listenerLabelCache, selectedLanguage.toLanguageCode()));
        data.put("labels", labels.isEmpty() ? (genres.isEmpty() ? List.of() : List.of(genres.getFirst())) : labels);
        String brand = stream.getLocalizedName().get(selectedLanguage.toLanguageCode());
        if (brand == null) {
            brand = stream.getLocalizedName().values().iterator().next();
        }
        AiOverriding overriddenAiDj = stream.getAiOverriding();
        if (overriddenAiDj != null && overriddenAiDj.getName() != null && !overriddenAiDj.getName().isEmpty()) {
            data.put("djName", overriddenAiDj.getName());
        } else {
            data.put("djName", agent.getName());
        }
        data.put("djVoiceId", agent.getTtsSetting().getDj().getId());
        ProfileOverriding overriddenProfile = stream.getProfileOverriding();
        String profileName;
        String profileDescription;
        if (overriddenProfile != null && !overriddenProfile.getName().isEmpty()) {
            profileName = overriddenProfile.getName();
        } else {
            profileName = profile.getName();
        }
        if (overriddenProfile != null && !overriddenProfile.getDescription().trim().isEmpty()) {
            profileDescription = overriddenProfile.getDescription();
        } else {
            profileDescription = profile.getDescription();
        }
        data.put("profileName", profileName);
        data.put("profileDescription", profileDescription != null ? profileDescription : "");
        data.put("stationBrand", brand);
        data.put("slugName", stream.getSlugName());
        data.put("country", stream.getCountry());
        data.put("language", selectedLanguage);
        data.put("random", random);
        data.put("perplexity", new PerplexitySearchHelper(perplexityApiClient));
        data.put("weather", new WeatherHelper(weatherApiClient, countryIso));
        data.put("news", new NewsHelper(worldNewsApiClient, countryIso, selectedLanguage.name()));
        UserAdHelper adsHelper = new UserAdHelper(dbClient, stream.getBrandId());
        data.put("ads", adsHelper);
        data.put("timeContext", TimeContextUtil.getCurrentMomentDetailed(stream.getTimeZone()));
        data.put("chatSummary", chatSummary != null ? chatSummary : "");
        if (song != null) {
            data.put("songTitle", song.getTitle());
            data.put("songArtist", song.getArtist());
            data.put("songDescription", song.getDescription());
        } else {
            data.put("songTitle", "");
            data.put("songArtist", "");
            data.put("songDescription", "");
        }
        data.put("songGenres", genres);
        data.put("songSharerName", sharerName != null ? sharerName : "");
        if (previousSong != null) {
            data.put("previousSongTitle", previousSong.getTitle() != null ? previousSong.getTitle() : "");
            data.put("previousSongArtist", previousSong.getArtist() != null ? previousSong.getArtist() : "");
        } else {
            data.put("previousSongTitle", "");
            data.put("previousSongArtist", "");
        }

        String rendered = groovyEngine.render(template, data, draftSlug).trim();
        return new DraftResult(rendered, adsHelper.getLastSelectedId(), adsHelper.getLastSelectedTitle(), adsHelper.getLastSelectedSlugName());
    }

    public Uni<Map<String, Object>> buildActionContext(
            SoundFragment song,
            ILiveStream stream,
            List<String> contextVars,
            LanguageTag language,
            AiAgent agent
    ) {
        Uni<List<String>> genresUni = resolveGenreNames(song, language.toLanguageCode());

        Uni<List<BrandListenerDTO>> listenersUni = listenerService.getBrandListeners(
                stream.getSlugName(), 500, 0, SuperUser.build(), null);

        Uni<List<String>> labelsUni = resolveLabels(song, language.toLanguageCode());

        return Uni.combine().all().unis(genresUni, listenersUni, labelsUni).asTuple().map(tuple -> {
            List<String> genres = tuple.getItem1();
            List<BrandListenerDTO> listeners = tuple.getItem2();
            List<String> labels = tuple.getItem3();
            Map<String, Object> ctx = new HashMap<>();
            ctx.put("songTitle", song.getTitle());
            ctx.put("songArtist", song.getArtist());
            ctx.put("songDescription", song.getDescription());
            ctx.put("songGenres", String.join(",", genres));
            ctx.put("country", stream.getCountry().getCountryName());
            String brand = stream.getLocalizedName().get(language.toLanguageCode());
            if (brand == null && !stream.getLocalizedName().isEmpty()) {
                brand = stream.getLocalizedName().values().iterator().next();
            }
            ctx.put("stationBrand", brand);
            ctx.put("djName", agent.getName());
            ZoneId tz = stream.getTimeZone();
            ctx.put("timeContext", TimeContextUtil.getCurrentMomentDetailed(tz));
            ctx.put("listeners", resolveListenerNames(listeners, language.toLanguageCode()));
            ctx.put("community", new ListenersHelper(listeners, listenerLabelCache, language.toLanguageCode()));
            ctx.put("labels", labels.isEmpty() ? (genres.isEmpty() ? List.of() : List.of(genres.getFirst())) : labels);
            LOGGER.infof("Action context resolved: %s", ctx);
            return ctx;
        });
    }

    private Uni<List<String>> resolveGenreNames(SoundFragment song, LanguageCode selectedLanguage) {
        String sql = "SELECT COALESCE(g.loc_name->>$2, g.identifier) AS name FROM __genres g " +
                "JOIN mixpla__sound_fragment_genres sfg ON g.id = sfg.genre_id " +
                "WHERE sfg.sound_fragment_id = $1";
        return dbClient.preparedQuery(sql)
                .execute(Tuple.of(song.getId(), selectedLanguage.name()))
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transform(row -> row.getString("name"))
                .filter(name -> name != null && !name.isBlank())
                .collect().asList();
    }

    private List<String> resolveListenerNames(List<BrandListenerDTO> listeners, LanguageCode lang) {
        if (listeners == null) {
            return List.of();
        }
        return listeners.stream()
                .map(dto -> {
                    var listener = dto.getListenerDTO();
                    if (listener == null) {
                        return null;
                    }
                    String name = listener.getLocalizedName().get(lang);
                    var userData = listener.getUserData();
                    if (name == null || name.isBlank()) {
                        if (userData != null) {
                            String v = userData.get("preferred_name");
                            if (v != null && !v.isBlank()) {
                                name = v;
                            }
                        }
                    }
                    if (name == null || name.isBlank()) {
                        return null;
                    }
                    if (userData != null) {
                        String country = userData.get("country");
                        if (country != null && !country.isBlank()) {
                            name = name + " (" + country + ")";
                        }
                    }
                    return name;
                })
                .filter(n -> n != null && !n.isBlank())
                .collect(Collectors.toList());
    }

    private Uni<List<String>> resolveLabels(SoundFragment song, LanguageCode selectedLanguage) {
        String sql = "SELECT l.loc_name->>$2 AS name FROM __labels l " +
                "JOIN mixpla__sound_fragment_labels sfl ON l.id = sfl.label_id " +
                "WHERE sfl.id = $1";
        return dbClient.preparedQuery(sql)
                .execute(Tuple.of(song.getId(), selectedLanguage.name()))
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transform(row -> row.getString("name"))
                .filter(name -> name != null && !name.isBlank())
                .collect().asList();
    }

}
