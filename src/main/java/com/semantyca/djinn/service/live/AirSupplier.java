package com.semantyca.djinn.service.live;

import com.semantyca.core.model.cnst.LanguageTag;
import com.semantyca.djinn.dto.AiDjStatsDTO;
import com.semantyca.djinn.dto.LiveRadioStationDTO;
import com.semantyca.djinn.dto.TtsDTO;
import com.semantyca.djinn.model.stream.OneTimeStream;
import com.semantyca.djinn.model.stream.RadioStream;
import com.semantyca.djinn.service.AiAgentService;
import com.semantyca.djinn.util.AiHelperUtils;
import com.semantyca.mixpla.model.aiagent.TTSSetting;
import com.semantyca.mixpla.model.brand.AiOverriding;
import com.semantyca.mixpla.model.cnst.StreamType;
import com.semantyca.mixpla.model.stream.IStream;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.user.SuperUser;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class AirSupplier {
    private static final Logger LOGGER = LoggerFactory.getLogger(AirSupplier.class);

    private final AiAgentService aiAgentService;
    private final OneTimeStreamSupplier oneTimeStreamSupplier;
    private final RadioStreamSupplier radioStreamSupplier;
    private final AiHelperService aiHelperService;

    private final Map<String, List<AiDjStatsDTO.StatusMessage>> aiDjMessagesTracker = new ConcurrentHashMap<>();
    private final Map<String, Integer> lastDeliveredSongsDurationTracker = new ConcurrentHashMap<>();

    @Inject
    public AirSupplier(
            AiAgentService aiAgentService,
            OneTimeStreamSupplier oneTimeStreamSupplier,
            RadioStreamSupplier radioStreamSupplier,
            AiHelperService aiHelperService
    ) {
        this.aiAgentService = aiAgentService;
        this.oneTimeStreamSupplier = oneTimeStreamSupplier;
        this.radioStreamSupplier = radioStreamSupplier;
        this.aiHelperService = aiHelperService;
    }
/*
    public Uni<LiveContainerDTO> getLiveRadioStationInfo(List<StreamStatus> statuses) {
        return Uni.createFrom().item(() ->
                radioStationPool.getOnlineStationsSnapshot().stream()
                        .filter(station -> station.getManagedBy() != ManagedBy.ITSELF)
                        .filter(station -> statuses.contains(station.getStatus()))
                        .collect(Collectors.toList())
        ).flatMap(stations -> {
            stations.forEach(station -> clearDashboardMessages(station.getSlugName()));
            LiveContainerDTO container = new LiveContainerDTO();
            if (stations.isEmpty()) {
                container.setRadioStations(List.of());
                return Uni.createFrom().item(container);
            }
            List<Uni<LiveRadioStationDTO>> stationUnis = stations.stream()
                    .map(this::buildLiveRadioStation)
                    .collect(Collectors.toList());
            return Uni.join().all(stationUnis).andFailFast()
                    .map(liveStations -> {
                        List<LiveRadioStationDTO> validStations = liveStations.stream()
                                .filter(liveStation -> {
                                    if (liveStation == null) {
                                        return false;
                                    } else if (liveStation.getPrompts() == null || liveStation.getPrompts().isEmpty()) {
                                        LOGGER.debug("Station '{}' filtered out: No active prompts", liveStation.getSlugName());
                                        return false;
                                    }
                                    return true;
                                })
                                .collect(Collectors.toList());
                        container.setRadioStations(validStations);
                        return container;
                    });
        });
    }*/

    private Uni<LiveRadioStationDTO> buildLiveRadioStation(IStream stream) {
        LiveRadioStationDTO liveRadioStation = new LiveRadioStationDTO();


        return aiAgentService.getById(stream.getAiAgentId(), SuperUser.build(), LanguageCode.en)
                .flatMap(agent -> {
                    LanguageTag broadcastingLanguage = AiHelperUtils.selectLanguageByWeight(agent);
                    liveRadioStation.setSlugName(stream.getSlugName());
                    liveRadioStation.setLanguageTag(broadcastingLanguage.tag());
                    liveRadioStation.setName(stream.getLocalizedName().get(broadcastingLanguage.toLanguageCode()));
                    TTSSetting ttsSetting = agent.getTtsSetting();
                    String additionalInstruction;
                    AiOverriding overriding = stream.getAiOverriding();
                    if (overriding != null) {
                        liveRadioStation.setDjName(String.format("%s overridden as %s", agent.getName(), overriding.getName()));
                        additionalInstruction = "\n\nAdditional instruction: " + overriding.getPrompt();
                    } else {
                        liveRadioStation.setDjName(agent.getName());
                        additionalInstruction = "";
                    }

                    if (stream.getAiOverriding() != null) {
                        additionalInstruction = "\n\nAdditional instruction: " +
                                stream.getAiOverriding().getPrompt();
                    }

                    aiHelperService.addAiDj(stream.getSlugName(), agent.getName());
                    Uni<Void> fetchPromptsUni;

                    if (stream instanceof OneTimeStream oneTimeStream) {
                        liveRadioStation.setStreamType(StreamType.ONE_TIME_STREAM);

                        fetchPromptsUni = oneTimeStreamSupplier.fetchOneTimeStreamPrompt(
                                        oneTimeStream,
                                        agent,
                                        broadcastingLanguage,
                                        additionalInstruction
                                )
                                .map(tuple -> {
                                    if (tuple != null) {
                                        int totalDuration = oneTimeStream.getLastDeliveredSongsDuration();
                                        lastDeliveredSongsDurationTracker.put(stream.getSlugName(), totalDuration);
                                        oneTimeStream.setLastDeliveryAt(LocalDateTime.now());

                                        liveRadioStation.setPrompts(tuple.getItem1());
                                        liveRadioStation.setInfo(tuple.getItem2());
                                    }
                                    return null;
                                });
                    } else if (stream instanceof RadioStream radioStream) {
                        liveRadioStation.setStreamType(StreamType.RADIO);
                        fetchPromptsUni = radioStreamSupplier.fetchStuffForRadioStream(
                                        radioStream,
                                        agent,
                                        broadcastingLanguage,
                                        additionalInstruction,
                                        this::addMessage
                                )
                                .map(tuple -> {
                                    if (tuple != null) {
                                        liveRadioStation.setPrompts(tuple.getItem1());
                                        liveRadioStation.setInfo(tuple.getItem2());
                                    }
                                    return null;
                                });
                    } else {
                        return Uni.createFrom().failure(
                                new IllegalStateException("Unsupported stream type")
                        );
                    }

                    return fetchPromptsUni.flatMap(ignored ->
                            aiAgentService.getDTO(
                                            agent.getCopilot(),
                                            SuperUser.build(),
                                            LanguageCode.en
                                    )
                                    .map(copilot -> {
                                        liveRadioStation.setTts(
                                                new TtsDTO(
                                                ttsSetting.getDj().getId(),
                                                copilot.getTtsSetting().getDj().getId(),
                                                copilot.getName(),
                                                ttsSetting.getDj().getEngineType()
                                        ));
                                        return liveRadioStation;
                                    })
                    );
                });
    }


    private void clearDashboardMessages(String stationSlug) {
        aiDjMessagesTracker.remove(stationSlug);
    }

    private void addMessage(String stationSlug, AiDjStatsDTO.MessageType type, String message) {
        aiDjMessagesTracker.computeIfAbsent(stationSlug, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(new AiDjStatsDTO.StatusMessage(type, message));
    }
}
