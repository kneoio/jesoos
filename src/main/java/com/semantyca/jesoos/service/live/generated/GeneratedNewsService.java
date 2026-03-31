package com.semantyca.jesoos.service.live.generated;

import com.semantyca.jesoos.agent.ElevenLabsClient;
import com.semantyca.jesoos.agent.GCPTTSClient;
import com.semantyca.jesoos.agent.ModelslabClient;
import com.semantyca.jesoos.config.JesoosConfig;
import com.semantyca.jesoos.repository.soundfragment.SoundFragmentRepository;
import com.semantyca.jesoos.service.AiAgentService;
import com.semantyca.jesoos.service.PromptService;
import com.semantyca.jesoos.service.live.scripting.DraftFactory;
import com.semantyca.jesoos.service.manipulation.FFmpegProvider;
import com.semantyca.jesoos.service.soundfragment.SoundFragmentService;
import com.semantyca.mixpla.model.aiagent.AiAgent;
import com.semantyca.mixpla.model.aiagent.Voice;
import com.semantyca.mixpla.model.cnst.PlaylistItemType;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class GeneratedNewsService extends AbstractGeneratedContentService {

    @Inject
    public GeneratedNewsService(
            PromptService promptService,
            SoundFragmentService soundFragmentService,
            ElevenLabsClient elevenLabsClient,
            ModelslabClient modelslabClient,
            GCPTTSClient gcpttsClient,
            JesoosConfig config,
            DraftFactory draftFactory,
            AiAgentService aiAgentService,
            SoundFragmentRepository soundFragmentRepository,
            FFmpegProvider ffmpegProvider
    ) {
        super(promptService, soundFragmentService, elevenLabsClient, modelslabClient,
                gcpttsClient, config, draftFactory, aiAgentService, soundFragmentRepository,
                ffmpegProvider);
    }

    GeneratedNewsService() {
        super(null, null, null, null, null, null, null, null, null, null);
    }

    @PostConstruct
    void init() {
        if (config != null) {
            initAnthropicClient();
        }
    }

    @Override
    protected PlaylistItemType getContentType() {
        return PlaylistItemType.NEWS;
    }

    @Override
    protected Voice getVoice(AiAgent agent) {
        return agent.getTtsSetting().getNewsReporter();
    }

    @Override
    protected String getSystemPrompt() {
        return "You are a professional radio news presenter";
    }
}
