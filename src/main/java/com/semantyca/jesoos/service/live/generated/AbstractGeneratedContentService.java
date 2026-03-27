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
import org.jboss.logging.Logger;

public abstract class AbstractGeneratedContentService implements IGeneratedContent {
    private static final Logger LOGGER = Logger.getLogger(AbstractGeneratedContentService.class);

    protected final PromptService promptService;
    protected final SoundFragmentService soundFragmentService;
    protected final ElevenLabsClient elevenLabsClient;
    protected final ModelslabClient modelslabClient;
    protected final GCPTTSClient gcpttsClient;
    protected final JesoosConfig config;
    protected final DraftFactory draftFactory;
    protected final AiAgentService aiAgentService;
    protected final SoundFragmentRepository soundFragmentRepository;
    protected final FFmpegProvider ffmpegProvider;

    protected AbstractGeneratedContentService(
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
        this.promptService = promptService;
        this.soundFragmentService = soundFragmentService;
        this.elevenLabsClient = elevenLabsClient;
        this.modelslabClient = modelslabClient;
        this.gcpttsClient = gcpttsClient;
        this.config = config;
        this.draftFactory = draftFactory;
        this.aiAgentService = aiAgentService;
        this.soundFragmentRepository = soundFragmentRepository;
        this.ffmpegProvider = ffmpegProvider;
    }

    protected abstract PlaylistItemType getContentType();
    protected abstract Voice getVoice(AiAgent agent);
    protected abstract String getSystemPrompt();


}

