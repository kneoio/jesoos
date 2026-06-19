package com.semantyca.jesoos.service.live.generated;

import com.semantyca.jesoos.config.JesoosConfig;
import com.semantyca.core.llm.AnthropicTextClient;
import com.semantyca.core.llm.GroqTextClient;
import com.semantyca.jesoos.external.ElevenLabsClient;
import com.semantyca.jesoos.external.GCPTTSClient;
import com.semantyca.jesoos.external.ModelslabClient;
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
public class GeneratedUserAdService extends AbstractGeneratedContentService {

    @Inject
    public GeneratedUserAdService(
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

    GeneratedUserAdService() {
        super(null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    @Override
    protected String buildArtistKey(String brandSlug, java.util.UUID promptId, com.semantyca.jesoos.service.live.scripting.DraftFactory.DraftResult draftResult) {
        return brandSlug + "_" + draftResult.selectedAdSlugName();
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

    @Override
    public Voice getVoice(AiAgent agent) {
        return agent.getTtsSetting().getAdReader();
    }
}
