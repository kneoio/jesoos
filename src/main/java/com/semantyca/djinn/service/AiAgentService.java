package com.semantyca.djinn.service;

import com.semantyca.djinn.dto.aiagent.AiAgentDTO;
import com.semantyca.djinn.dto.aiagent.LanguagePreferenceDTO;
import com.semantyca.djinn.dto.aiagent.TTSSettingDTO;
import com.semantyca.djinn.dto.aiagent.VoiceDTO;
import com.semantyca.djinn.repository.AiAgentRepository;
import com.semantyca.mixpla.model.aiagent.AiAgent;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.user.IUser;
import io.kneo.core.model.user.SuperUser;
import io.kneo.core.service.AbstractService;
import io.kneo.core.service.UserService;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class AiAgentService extends AbstractService<AiAgent, AiAgentDTO> {
    private static final Logger LOGGER = LoggerFactory.getLogger(AiAgentService.class);

    private final AiAgentRepository repository;

    @Inject
    public AiAgentService(
            UserService userService,
            AiAgentRepository repository
    ) {
        super(userService);
        this.repository = repository;
    }

    public Uni<List<AiAgentDTO>> getAll(final int limit, final int offset, final IUser user) {
        return repository.getAll(limit, offset, false, user)
                .chain(list -> {
                    if (list.isEmpty()) return Uni.createFrom().item(List.of());
                    List<Uni<AiAgentDTO>> unis = list.stream()
                            .map(this::mapToDTO)
                            .collect(Collectors.toList());
                    return Uni.join().all(unis).andFailFast();
                });
    }

    public Uni<Integer> getAllCount(final IUser user) {
        return repository.getAllCount(user, false);
    }

    public Uni<List<AiAgent>> getAll(final int limit, final int offset) {
        return repository.getAll(limit, offset, false, SuperUser.build());
    }

    public Uni<AiAgent> getById(UUID id, IUser user, LanguageCode language) {
        return repository.findById(id, user, false);
    }

    @Override
    public Uni<Integer> delete(String id, IUser user) {
        return null;
    }

    @Override
    public Uni<AiAgentDTO> getDTO(UUID id, IUser user, LanguageCode language) {
        return repository.findById(id, user, false).chain(this::mapToDTO);
    }

    private Uni<AiAgentDTO> mapToDTO(AiAgent doc) {
        return Uni.combine().all().unis(
                userService.getUserName(doc.getAuthor()),
                userService.getUserName(doc.getLastModifier())
        ).asTuple().map(tuple -> {
            AiAgentDTO dto = new AiAgentDTO();
            dto.setId(doc.getId());
            dto.setAuthor(tuple.getItem1());
            dto.setRegDate(doc.getRegDate());
            dto.setLastModifier(tuple.getItem2());
            dto.setLastModifiedDate(doc.getLastModifiedDate());
            dto.setName(doc.getName());
            
            if (doc.getPreferredLang() != null && !doc.getPreferredLang().isEmpty()) {
                List<LanguagePreferenceDTO> langPrefDTOs = doc.getPreferredLang().stream()
                        .map(pref -> new LanguagePreferenceDTO(pref.getLanguageTag().tag(), pref.getWeight()))
                        .toList();
                dto.setPreferredLang(langPrefDTOs);
            }
            
            dto.setLlmType(doc.getLlmType().name());

            if (doc.getCopilot() != null) dto.setCopilot(doc.getCopilot());

            if (doc.getTtsSetting() != null) {
                TTSSettingDTO ttsSettingDTO = new TTSSettingDTO();
                if (doc.getTtsSetting().getDj() != null) {
                    VoiceDTO djVoice = new VoiceDTO();
                    djVoice.setId(doc.getTtsSetting().getDj().getId());
                    djVoice.setName(doc.getTtsSetting().getDj().getName());
                    djVoice.setEngineType(doc.getTtsSetting().getDj().getEngineType());
                    ttsSettingDTO.setDj(djVoice);
                }
                if (doc.getTtsSetting().getCopilot() != null) {
                    VoiceDTO copilotVoice = new VoiceDTO();
                    copilotVoice.setId(doc.getTtsSetting().getCopilot().getId());
                    copilotVoice.setName(doc.getTtsSetting().getCopilot().getName());
                    copilotVoice.setEngineType(doc.getTtsSetting().getCopilot().getEngineType());
                    ttsSettingDTO.setCopilot(copilotVoice);
                }
                if (doc.getTtsSetting().getNewsReporter() != null) {
                    VoiceDTO newsReporterVoice = new VoiceDTO();
                    newsReporterVoice.setId(doc.getTtsSetting().getNewsReporter().getId());
                    newsReporterVoice.setName(doc.getTtsSetting().getNewsReporter().getName());
                    newsReporterVoice.setEngineType(doc.getTtsSetting().getNewsReporter().getEngineType());
                    ttsSettingDTO.setNewsReporter(newsReporterVoice);
                }
                if (doc.getTtsSetting().getWeatherReporter() != null) {
                    VoiceDTO weatherReporterVoice = new VoiceDTO();
                    weatherReporterVoice.setId(doc.getTtsSetting().getWeatherReporter().getId());
                    weatherReporterVoice.setName(doc.getTtsSetting().getWeatherReporter().getName());
                    weatherReporterVoice.setEngineType(doc.getTtsSetting().getWeatherReporter().getEngineType());
                    ttsSettingDTO.setWeatherReporter(weatherReporterVoice);
                }
                dto.setTtsSetting(ttsSettingDTO);
            }

            if (doc.getLabels() != null && !doc.getLabels().isEmpty()) {
                dto.setLabels(doc.getLabels());
            }

            return dto;
        });
    }
}
