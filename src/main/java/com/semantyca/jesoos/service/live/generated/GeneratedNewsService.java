package com.semantyca.jesoos.service.live.generated;

import com.semantyca.jesoos.agent.ElevenLabsClient;
import com.semantyca.jesoos.agent.GCPTTSClient;
import com.semantyca.jesoos.agent.ModelslabClient;
import com.semantyca.jesoos.config.JesoosConfig;
import com.semantyca.jesoos.service.AiAgentService;
import com.semantyca.jesoos.service.PromptService;
import com.semantyca.jesoos.service.live.IntroTtsGenerator;
import com.semantyca.jesoos.service.live.scripting.DraftFactory;
import com.semantyca.jesoos.service.soundfragment.SoundFragmentService;
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
            IntroTtsGenerator introTtsGenerator,
            DraftFactory draftFactory,
            AiAgentService aiAgentService
    ) {
        super(
                promptService,
                soundFragmentService,
                elevenLabsClient,
                modelslabClient,
                gcpttsClient,
                introTtsGenerator,
                config,
                draftFactory,
                aiAgentService);
    }

    GeneratedNewsService() {
        super( null, null, null, null, null, null, null, null, null);
    }

    @PostConstruct
    void init() {
        if (config != null) {
            initAnthropicClient();
        }
    }

    @Override
    protected String getSystemPrompt() {
        return "You are a professional radio news presenter";
    }
}
