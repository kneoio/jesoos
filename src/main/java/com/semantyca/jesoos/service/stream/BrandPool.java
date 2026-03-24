package com.semantyca.jesoos.service.stream;

import com.semantyca.core.model.cnst.LanguageCode;
import com.semantyca.core.model.cnst.LanguageTag;
import com.semantyca.core.model.user.SuperUser;
import com.semantyca.jesoos.messaging.MetricPublisher;
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
import com.semantyca.mixpla.dto.queue.metric.MetricEventType;
import com.semantyca.mixpla.model.cnst.StreamStatus;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;


import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@ApplicationScoped
public class BrandPool {
    private static final Logger LOGGER = Logger.getLogger(BrandPool.class);
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

    @Inject
    private MetricPublisher metricPublisher;

    public Uni<ILiveAgenda> initializeRadioAgenda(String brandName) {
        LOGGER.infof("Attempting to initialize agenda for brand: {}", brandName);

        return Uni.createFrom().item(brandName)
                .onItem().transformToUni(bn -> {
                    ILiveAgenda existingStation = pool.get(bn);
                    if (existingStation != null) {
                        // Station already exists - ignore duplicate request
                        LOGGER.infof("Stream {} already exists in pool (status: {}). Ignoring duplicate start request.", 
                            bn, existingStation.getStatus());
                        return Uni.createFrom().item(existingStation);
                    }

                    // No existing station - create new one
                    return brandService.getBySlugName(bn)
                            .onItem().transformToUni(brand -> {
                                if (brand == null) {
                                    LOGGER.warnf("Brand with brandName {} not found. Cannot initialize.", bn);
                                    pool.remove(bn);
                                    return Uni.createFrom().failure(new RuntimeException("Station not found: " + bn));
                                }

                                RadioStream newStream = new RadioStream(brand);
                                newStream.setStatus(StreamStatus.WARMING_UP);
                                pool.put(bn, newStream);

                                return streamAgendaService.buildAgenda(brand.getId(), brand.getScripts().getFirst().getScriptId(), SuperUser.build())
                                        .invoke(schedule -> {
                                            newStream.setAgenda(schedule);
                                            LOGGER.infof("BrandPool: New station created for '{}': {} scenes, {} songs",
                                                    newStream.getSlugName(),schedule.getTotalScenes(), schedule.getTotalSongs());
                                        })
                                        .map(schedule -> (ILiveAgenda) newStream);
                            });
                })
                .onItem().invoke(agenda -> metricPublisher.publishMetric(brandName, MetricEventType.INFORMATION, "station_start", Map.of("status", agenda.getStatus().name())))
                .onFailure().invoke(failure -> LOGGER.errorf("Overall failure to initialize station {}: {}", brandName, failure.getMessage(), failure));
    }

    public Uni<ILiveAgenda> initializeStream(ILiveAgenda oneTimeStream) {
        return Uni.createFrom().item(oneTimeStream)
                .onItem().transformToUni(ots -> {
                    ILiveAgenda stationAlreadyActive = pool.get(ots.getSlugName());
                    if (stationAlreadyActive != null &&
                            (stationAlreadyActive.getStatus() == StreamStatus.ON_LINE ||
                                    stationAlreadyActive.getStatus() == StreamStatus.WARMING_UP)) {
                        LOGGER.infof("Stream {} already active (status: {}). Returning existing instance.", ots.getSlugName(), stationAlreadyActive.getStatus());
                        return Uni.createFrom().item(stationAlreadyActive);
                    }

                    return oneTimeStreamService.getBySlugName(ots.getSlugName())
                            .onItem().transformToUni(stream -> {
                                
                                if (stream.getAiAgentId() != null) {
                                    return aiAgentService.getById(stream.getAiAgentId(), SuperUser.build(), LanguageCode.en)
                                            .onItem().transform(agent -> {
                                                LanguageTag selectedLanguage = AiHelperUtils.selectLanguageByWeight(agent);
                                                stream.setStreamLanguage(selectedLanguage);
                                                LOGGER.infof("Set stream language to '{}' for stream '{}' based on AI agent '{}'", 
                                                    selectedLanguage.tag(), stream.getSlugName(), agent.getName());
                                                return stream;
                                            })
                                            .onFailure().invoke(failure -> {
                                                LOGGER.warnf("Failed to resolve AI agent for stream '{}', using default language: {}", 
                                                    stream.getSlugName(), failure.getMessage());
                                                stream.setStreamLanguage(LanguageTag.EN_US);
                                            })
                                            .onFailure().recoverWithItem(() -> {
                                                stream.setStreamLanguage(LanguageTag.EN_US);
                                                return stream;
                                            });
                                } else {
                                    LOGGER.warnf("No AI Agent ID set for stream '{}', using default language", stream.getSlugName());
                                    stream.setStreamLanguage(LanguageTag.EN_US);
                                    return Uni.createFrom().item(stream);
                                }
                            })
                            .onItem().transformToUni(stream -> {

                                ILiveAgenda finalStationToUse = pool.compute(ots.getSlugName(), (key, currentInPool) -> {
                                    if (currentInPool != null &&
                                            (currentInPool.getStatus() == StreamStatus.ON_LINE ||
                                                    currentInPool.getStatus() == StreamStatus.WARMING_UP)) {
                                        LOGGER.infof("Stream {} was concurrently initialized and is active in pool. Using that instance.", key);
                                        return currentInPool;
                                    }

                                    LOGGER.infof("BrandPool: Creating new StreamManager instance for stream {}.", key);

                                    return stream;
                                });
                                return Uni.createFrom().item(finalStationToUse);
                            });
                })
                .onFailure().invoke(failure -> LOGGER.errorf("Overall failure to initialize stream {}: {}", oneTimeStream.getSlugName(), failure.getMessage(), failure));
    }

    public Uni<ILiveAgenda> get(String brandName) {
        ILiveAgenda stream = pool.get(brandName);
        return Uni.createFrom().item(stream);
    }

    public Uni<ILiveAgenda> stopAndRemove(String brandName) {
        LOGGER.infof("Attempting to stop and remove station: {}", brandName);
        ILiveAgenda liveAgenda = pool.remove(brandName);

        if (liveAgenda != null) {
            liveAgenda.setStatus(StreamStatus.OFF_LINE);
            metricPublisher.publishMetric(brandName, MetricEventType.INFORMATION, "station_stop", Map.of("status", liveAgenda.getStatus().name()));
            
            if (liveAgenda instanceof OneTimeStream oneTimeStream) {
                return oneTimeStreamRepository.getBySlugName(brandName)
                        .onItem().invoke(repoStream -> {
                            if (repoStream != null) {
                                StreamStatus newStatus = oneTimeStream.getStatus() == StreamStatus.FINISHED
                                    ? StreamStatus.FINISHED 
                                    : StreamStatus.OFF_LINE;
                                repoStream.setStatus(newStatus);
                                LOGGER.infof("Updated repository status to {} for OneTimeStream station: {}", newStatus, brandName);
                            }
                        })
                        .replaceWith(liveAgenda);
            }
            
            return Uni.createFrom().item(liveAgenda);
        } else {
            LOGGER.warnf("Station {} not found in pool during stopAndRemove.", brandName);
            return Uni.createFrom().nullItem();
        }
    }

    public Collection<ILiveAgenda> getOnlineStationsSnapshot() {
        return new ArrayList<>(pool.values());
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
