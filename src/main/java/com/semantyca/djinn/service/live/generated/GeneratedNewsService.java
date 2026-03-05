package com.semantyca.djinn.service.live.generated;

import com.semantyca.djinn.agent.GCPTTSClient;
import com.semantyca.djinn.agent.ModelslabClient;
import com.semantyca.djinn.config.DjinnConfig;
import com.semantyca.djinn.repository.soundfragment.SoundFragmentRepository;
import com.semantyca.djinn.service.AiAgentService;
import com.semantyca.djinn.service.PromptService;
import com.semantyca.djinn.service.live.scripting.DraftFactory;
import com.semantyca.djinn.service.manipulation.FFmpegProvider;
import com.semantyca.djinn.service.manipulation.mixing.AudioConcatenator;
import com.semantyca.djinn.service.soundfragment.SoundFragmentService;
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
            ModelslabClient modelslabClient,
            GCPTTSClient gcpttsClient,
            DjinnConfig config,
            DraftFactory draftFactory,
            AiAgentService aiAgentService,
            SoundFragmentRepository soundFragmentRepository,
            FFmpegProvider ffmpegProvider,
            AudioConcatenator audioConcatenator
    ) {
        super(promptService, soundFragmentService, modelslabClient,
                gcpttsClient, config, draftFactory, aiAgentService, soundFragmentRepository,
                ffmpegProvider, audioConcatenator);
    }

    public GeneratedNewsService() {
        super(null, null, null, null,  null, null, null, null, null, null);
    }

    @Override
    protected String getIntroJingleResource() {
        return "News_Intro_Jingle.wav";
    }

    @Override
    protected String getBackgroundMusicResource() {
        return "News_Cycle_Loop.wav";
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