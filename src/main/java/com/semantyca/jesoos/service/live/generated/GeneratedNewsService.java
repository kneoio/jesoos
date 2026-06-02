package com.semantyca.jesoos.service.live.generated;

import com.semantyca.jesoos.external.ElevenLabsClient;
import com.semantyca.jesoos.external.GCPTTSClient;
import com.semantyca.jesoos.external.AnthropicTextClient;
import com.semantyca.jesoos.external.GroqTextClient;
import com.semantyca.jesoos.external.ModelslabClient;
import com.semantyca.jesoos.config.JesoosConfig;
import com.semantyca.jesoos.repository.UserAdRepository;
import com.semantyca.jesoos.repository.soundfragment.SoundFragmentRepository;
import com.semantyca.jesoos.service.AiAgentService;
import com.semantyca.jesoos.service.PromptService;
import com.semantyca.jesoos.service.live.IntroTtsGenerator;
import com.semantyca.jesoos.service.live.scripting.DraftFactory;
import com.semantyca.jesoos.service.manipulation.FFmpegProvider;
import com.semantyca.jesoos.service.soundfragment.SoundFragmentService;
import com.semantyca.mixpla.model.aiagent.AiAgent;
import com.semantyca.mixpla.model.aiagent.Voice;
import com.semantyca.mixpla.model.cnst.PlaylistItemType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class GeneratedNewsService extends AbstractGeneratedContentService {

    @Inject
    public GeneratedNewsService(
            PromptService promptService,
            SoundFragmentService soundFragmentService,
            SoundFragmentRepository soundFragmentRepository,
            ElevenLabsClient elevenLabsClient,
            ModelslabClient modelslabClient,
            GCPTTSClient gcpttsClient,
            JesoosConfig config,
            AnthropicTextClient anthropicTextClient,
            GroqTextClient groqTextClient,
            IntroTtsGenerator introTtsGenerator,
            DraftFactory draftFactory,
            AiAgentService aiAgentService,
            FFmpegProvider ffmpegProvider,
            UserAdRepository userAdRepository
    ) {
        super(
                promptService,
                soundFragmentService,
                soundFragmentRepository,
                elevenLabsClient,
                modelslabClient,
                gcpttsClient,
                introTtsGenerator,
                config,
                anthropicTextClient,
                groqTextClient,
                draftFactory,
                aiAgentService,
                ffmpegProvider,
                userAdRepository);
    }

    GeneratedNewsService() {
        super(null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    @Override
    protected String getSystemPrompt() {
        return "You are a professional radio news presenter";
    }

    @Override
    protected PlaylistItemType getFragmentType() {
        return PlaylistItemType.NEWS;
    }

    @Override
    public Voice getVoice(AiAgent agent) {
        return agent.getTtsSetting().getNewsReporter();
    }
}
