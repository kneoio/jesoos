package com.semantyca.jesoos.service.live.generated;

import com.semantyca.core.llm.AnthropicTextClient;
import com.semantyca.core.llm.GroqTextClient;
import com.semantyca.core.model.cnst.LanguageTag;
import com.semantyca.jesoos.config.JesoosConfig;
import com.semantyca.jesoos.external.ElevenLabsClient;
import com.semantyca.jesoos.external.GCPTTSClient;
import com.semantyca.jesoos.external.ModelslabClient;
import com.semantyca.jesoos.repository.UserAdRepository;
import com.semantyca.jesoos.repository.soundfragment.SoundFragmentRepository;
import com.semantyca.jesoos.service.AiAgentService;
import com.semantyca.jesoos.service.BrandService;
import com.semantyca.jesoos.service.PromptService;
import com.semantyca.jesoos.service.live.IntroTtsGenerator;
import com.semantyca.jesoos.service.live.scripting.DraftFactory;
import com.semantyca.jesoos.service.manipulation.FFmpegProvider;
import com.semantyca.jesoos.service.soundfragment.SoundFragmentService;
import com.semantyca.mixpla.model.aiagent.AiAgent;
import com.semantyca.mixpla.model.aiagent.Voice;
import com.semantyca.mixpla.model.cnst.PlaylistItemType;
import com.semantyca.mixpla.model.cnst.TTSEngineType;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
public class TwoSpeakersGeneratedContentService extends AbstractGeneratedContentService {

    private static final Pattern SPEAKER_LINE = Pattern.compile("(?im)^\\s*speaker\\s*([12])\\s*:\\s*(.+)$");

    @Inject
    public TwoSpeakersGeneratedContentService(
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
            UserAdRepository userAdRepository,
            BrandService brandService
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
                userAdRepository,
                brandService);
    }

    TwoSpeakersGeneratedContentService() {
        super(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    @Override
    protected String getSystemPrompt() {
        return "You are producing a two-host spoken dialogue. Output ONLY spoken dialogue lines, "
                + "each line prefixed with 'Speaker 1:' or 'Speaker 2:', alternating naturally between the two hosts. "
                + "Do not include any narration, stage directions, headings, or text outside the dialogue lines.";
    }

    @Override
    protected PlaylistItemType getFragmentType() {
        return PlaylistItemType.PODCAST;
    }

    @Override
    public Voice getVoice(AiAgent agent) {
        return agent.getTtsSetting().getPodcastSpeaker1();
    }

    @Override
    protected Uni<String> generateContentAudio(String text, AiAgent agent, LanguageTag airLanguage, String sceneTitle, UUID traceId, String slug) {
        Voice speaker1 = agent.getTtsSetting().getPodcastSpeaker1();
        Voice speaker2 = agent.getTtsSetting().getPodcastSpeaker2();
        if (speaker1 == null || speaker2 == null) {
            return Uni.createFrom().failure(new IllegalStateException(
                    "Podcast requires both podcastSpeaker1 and podcastSpeaker2 to be configured on the agent's TTS setting"));
        }
        if (speaker1.getEngineType() != TTSEngineType.ELEVENLABS || speaker2.getEngineType() != TTSEngineType.ELEVENLABS) {
            return Uni.createFrom().failure(new IllegalStateException(
                    "Podcast dialogue is only supported by the ELEVENLABS TTS engine; both podcast speakers must use ELEVENLABS"));
        }

        List<ElevenLabsClient.DialogueSegment> segments = parseDialogue(text, speaker1.getId(), speaker2.getId());
        if (segments.isEmpty()) {
            return Uni.createFrom().failure(new IllegalStateException(
                    "Podcast dialogue text produced no 'Speaker 1:'/'Speaker 2:' lines for scene: " + sceneTitle));
        }

        return introTtsGenerator.generateDialogueAudio(segments, sceneTitle, traceId, slug);
    }

    private List<ElevenLabsClient.DialogueSegment> parseDialogue(String text, String speaker1VoiceId, String speaker2VoiceId) {
        List<ElevenLabsClient.DialogueSegment> segments = new ArrayList<>();
        Matcher matcher = SPEAKER_LINE.matcher(text);
        while (matcher.find()) {
            String voiceId = "1".equals(matcher.group(1)) ? speaker1VoiceId : speaker2VoiceId;
            segments.add(new ElevenLabsClient.DialogueSegment(voiceId, matcher.group(2).trim()));
        }
        return segments;
    }
}
