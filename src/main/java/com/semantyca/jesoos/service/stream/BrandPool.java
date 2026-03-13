package com.semantyca.jesoos.service.stream;

import com.semantyca.core.model.cnst.LanguageCode;
import com.semantyca.core.model.cnst.LanguageTag;
import com.semantyca.core.model.user.SuperUser;
import com.semantyca.jesoos.model.stats.BroadcastingStats;
import com.semantyca.jesoos.model.stream.ILiveAgenda;
import com.semantyca.jesoos.model.stream.OneTimeStream;
import com.semantyca.jesoos.model.stream.RadioStream;
import com.semantyca.jesoos.model.stream.StreamAgenda;
import com.semantyca.jesoos.repository.OneTimeStreamRepository;
import com.semantyca.jesoos.service.AiAgentService;
import com.semantyca.jesoos.service.BrandService;
import com.semantyca.jesoos.service.OneTimeStreamService;
import com.semantyca.jesoos.util.AiHelperUtils;
import com.semantyca.mixpla.model.cnst.StreamStatus;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@ApplicationScoped
public class BrandPool {
    private static final Logger LOGGER = LoggerFactory.getLogger(BrandPool.class);
    private final ConcurrentHashMap<String, ILiveAgenda> pool = new ConcurrentHashMap<>();

    @Inject
    BrandService brandService;

    @Inject
    private StreamAgendaService streamAgendaService;

    @Inject
    private OneTimeStreamService oneTimeStreamService;

    @Inject
    private OneTimeStreamRepository oneTimeStreamRepository;

    @Inject
    private AiAgentService aiAgentService;

    public Uni<ILiveAgenda> initializeRadio(String brandName) {
        LOGGER.info("Attempting to initialize Radio Stream for brand: {}", brandName);

        return Uni.createFrom().item(brandName)
                .onItem().transformToUni(bn -> {
                    ILiveAgenda stationAlreadyActive = pool.get(bn);
                    if (stationAlreadyActive != null &&
                            (stationAlreadyActive.getStatus() == StreamStatus.ON_LINE ||
                                    stationAlreadyActive.getStatus() == StreamStatus.WARMING_UP)) {
                        LOGGER.info("Radio Stream {} already active (status: {}) or warming up. Returning existing instance from initial check.", bn, stationAlreadyActive.getStatus());
                        return Uni.createFrom().item(stationAlreadyActive);
                    }

                    return brandService.getBySlugName(bn)
                            .onItem().transformToUni(brand -> {
                                if (brand == null) {
                                    LOGGER.warn("Brand with brandName {} not found in database. Cannot initialize.", bn);
                                    pool.remove(bn);
                                    return Uni.createFrom().failure(new RuntimeException("Station not found in DB: " + bn));
                                }

                                ILiveAgenda finalStationToUse = pool.compute(bn, (key, currentInPool) -> {
                                    if (currentInPool != null &&
                                            (currentInPool.getStatus() == StreamStatus.ON_LINE ||
                                                    currentInPool.getStatus() == StreamStatus.WARMING_UP)) {
                                        LOGGER.info("Radio stream {} was concurrently initialized and is active in pool. Using that instance.", key);
                                        return currentInPool;
                                    }

                                    LOGGER.info("RadioStationPool: Creating new StreamManager instance for brand {}.", key);
                                    RadioStream radioStream = new RadioStream(brand);
                                    LOGGER.info("RadioStationPool: StreamManager for {} instance created and StreamManager.initialize() called. Status should be WARMING_UP", key);
                                    return radioStream;
                                });

                                if (finalStationToUse instanceof RadioStream radioStream && radioStream.getAgenda() == null) {
                                    LOGGER.info("RadioStationPool: Building looped schedule for RadioStream '{}'", radioStream.getSlugName());
                                    return streamAgendaService.buildRadioLiveAgenda(brand.getId(), brand.getScripts().getFirst().getScriptId(), SuperUser.build())
                                            .invoke(schedule -> {
                                                radioStream.setAgenda(schedule);
                                                LOGGER.info("RadioStationPool: Schedule set for '{}': {} scenes, {} songs",
                                                        radioStream.getSlugName(),
                                                        schedule != null ? schedule.getTotalScenes() : 0,
                                                        schedule != null ? schedule.getTotalSongs() : 0);
                                            })
                                            .map(schedule -> (ILiveAgenda) radioStream);
                                }
                                return Uni.createFrom().item(finalStationToUse);
                            });
                })
                .onFailure().invoke(failure -> LOGGER.error("Overall failure to initialize station {}: {}", brandName, failure.getMessage(), failure));
    }

    public Uni<ILiveAgenda> initializeStream(ILiveAgenda oneTimeStream) {
        return Uni.createFrom().item(oneTimeStream)
                .onItem().transformToUni(ots -> {
                    ILiveAgenda stationAlreadyActive = pool.get(ots.getSlugName());
                    if (stationAlreadyActive != null &&
                            (stationAlreadyActive.getStatus() == StreamStatus.ON_LINE ||
                                    stationAlreadyActive.getStatus() == StreamStatus.WARMING_UP)) {
                        LOGGER.info("Stream {} already active (status: {}). Returning existing instance.", ots.getSlugName(), stationAlreadyActive.getStatus());
                        return Uni.createFrom().item(stationAlreadyActive);
                    }

                    return oneTimeStreamService.getBySlugName(ots.getSlugName())
                            .onItem().transformToUni(stream -> {
                                
                                if (stream.getAiAgentId() != null) {
                                    return aiAgentService.getById(stream.getAiAgentId(), SuperUser.build(), LanguageCode.en)
                                            .onItem().transform(agent -> {
                                                LanguageTag selectedLanguage = AiHelperUtils.selectLanguageByWeight(agent);
                                                stream.setStreamLanguage(selectedLanguage);
                                                LOGGER.info("Set stream language to '{}' for stream '{}' based on AI agent '{}'", 
                                                    selectedLanguage.tag(), stream.getSlugName(), agent.getName());
                                                return stream;
                                            })
                                            .onFailure().invoke(failure -> {
                                                LOGGER.warn("Failed to resolve AI agent for stream '{}', using default language: {}", 
                                                    stream.getSlugName(), failure.getMessage());
                                                stream.setStreamLanguage(LanguageTag.EN_US);
                                            })
                                            .onFailure().recoverWithItem(() -> {
                                                stream.setStreamLanguage(LanguageTag.EN_US);
                                                return stream;
                                            });
                                } else {
                                    LOGGER.warn("No AI Agent ID set for stream '{}', using default language", stream.getSlugName());
                                    stream.setStreamLanguage(LanguageTag.EN_US);
                                    return Uni.createFrom().item(stream);
                                }
                            })
                            .onItem().transformToUni(stream -> {

                                ILiveAgenda finalStationToUse = pool.compute(ots.getSlugName(), (key, currentInPool) -> {
                                    if (currentInPool != null &&
                                            (currentInPool.getStatus() == StreamStatus.ON_LINE ||
                                                    currentInPool.getStatus() == StreamStatus.WARMING_UP)) {
                                        LOGGER.info("Stream {} was concurrently initialized and is active in pool. Using that instance.", key);
                                        return currentInPool;
                                    }

                                    LOGGER.info("RadioStationPool: Creating new StreamManager instance for stream {}.", key);

                                    return stream;
                                });
                                return Uni.createFrom().item(finalStationToUse);
                            });
                })
                .onFailure().invoke(failure -> LOGGER.error("Overall failure to initialize stream {}: {}", oneTimeStream.getSlugName(), failure.getMessage(), failure));
    }

    public Uni<ILiveAgenda> get(String brandName) {
        ILiveAgenda stream = pool.get(brandName);
        return Uni.createFrom().item(stream);
    }

    public Uni<ILiveAgenda> stopAndRemove(String brandName) {
        LOGGER.info("Attempting to stop and remove station: {}", brandName);
        ILiveAgenda brand = pool.remove(brandName);

        if (brand != null) {
            LOGGER.info("Station {} found in pool and removed. Shutting down StreamManager.", brandName);

            brand.setStatus(StreamStatus.OFF_LINE);
            
            if (brand instanceof OneTimeStream oneTimeStream) {
                return oneTimeStreamRepository.getBySlugName(brandName)
                        .onItem().invoke(repoStream -> {
                            if (repoStream != null) {
                                // Preserve the current status if it's already FINISHED, otherwise set to OFF_LINE
                                StreamStatus newStatus = oneTimeStream.getStatus() == StreamStatus.FINISHED 
                                    ? StreamStatus.FINISHED 
                                    : StreamStatus.OFF_LINE;
                                repoStream.setStatus(newStatus);
                                LOGGER.info("Updated repository status to {} for OneTimeStream station: {}", newStatus, brandName);
                            }
                        })
                        .replaceWith(brand);
            }
            
            return Uni.createFrom().item(brand);
        } else {
            LOGGER.warn("Station {} not found in pool during stopAndRemove.", brandName);
            return Uni.createFrom().nullItem();
        }
    }

    public Collection<ILiveAgenda> getOnlineStationsSnapshot() {
        return new ArrayList<>(pool.values());
    }

    public Set<String> getActiveSnapshot() {
        return new HashSet<>(pool.keySet());
    }

    public ILiveAgenda getStation(String slugName) {
        return pool.get(slugName);
    }

    public Uni<BroadcastingStats> getLiveStatus(String name) {
        BroadcastingStats stats = new BroadcastingStats();
        ILiveAgenda brand = pool.get(name);
        if (brand != null) {
            stats.setStatus(brand.getStatus());
        } else {
            stats.setStatus(StreamStatus.OFF_LINE);
            stats.setAiControlAllowed(false);
        }
        return Uni.createFrom().item(stats);
    }

    public Map<String, StreamAgenda> getAll() {
        return pool.entrySet().stream()
                .filter(entry -> entry.getValue().getAgenda() != null)
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().getAgenda()));
    }
}
