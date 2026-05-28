package com.semantyca.jesoos.service.live.generated;

import com.semantyca.jesoos.external.AnthropicTextClient;
import com.semantyca.jesoos.external.ElevenLabsClient;
import com.semantyca.jesoos.external.GCPTTSClient;
import com.semantyca.jesoos.external.GroqTextClient;
import com.semantyca.jesoos.external.ModelslabClient;
import com.semantyca.jesoos.config.JesoosConfig;
import com.semantyca.jesoos.repository.soundfragment.SoundFragmentRepository;
import com.semantyca.jesoos.service.AiAgentService;
import com.semantyca.jesoos.service.PromptService;
import com.semantyca.jesoos.service.live.IntroTtsGenerator;
import com.semantyca.jesoos.service.live.scripting.DraftFactory;
import com.semantyca.jesoos.service.manipulation.FFmpegProvider;
import com.semantyca.jesoos.service.soundfragment.SoundFragmentService;
import com.semantyca.mixpla.model.cnst.PlaylistItemType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class GeneratedAdService extends AbstractGeneratedContentService {

    @Inject
    public GeneratedAdService(
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
            FFmpegProvider ffmpegProvider
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
                ffmpegProvider);
    }

    GeneratedAdService() {
        super(null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    @Override
    protected String getSystemPrompt() {
        return "You are a radio host reading a listener's advertisement on air. " +
                "Read it naturally and professionally, as if introducing a sponsor. Keep it concise.";
    }

    @Override
    protected PlaylistItemType getFragmentType() {
        return PlaylistItemType.ADVERTISEMENT;
    }
}
