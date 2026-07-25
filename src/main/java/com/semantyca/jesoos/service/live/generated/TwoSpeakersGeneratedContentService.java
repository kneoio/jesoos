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
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class TwoSpeakersGeneratedContentService extends AbstractGeneratedContentService {

    private static final String SPEAKER_1_REF = "__SPEAKER_1__";
    private static final String SPEAKER_2_REF = "__SPEAKER_2__";

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
        return "You are producing a two-host spoken dialogue. Return ONLY a single JSON object of the form "
                + "{\"inputs\":[{\"voice_id\":\"" + SPEAKER_1_REF + "\",\"text\":\"...\"},"
                + "{\"voice_id\":\"" + SPEAKER_2_REF + "\",\"text\":\"...\"}]}. "
                + "Use exactly the tokens " + SPEAKER_1_REF + " and " + SPEAKER_2_REF + " for the two hosts, one per turn, "
                + "alternating and starting with " + SPEAKER_1_REF + ". The 'text' field is the spoken line only. "
                + "Output nothing but the JSON object: no prose, no markdown, no code fences.";
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

        List<ElevenLabsClient.DialogueSegment> segments;
        try {
            segments = parseDialogueJson(text, speaker1.getId(), speaker2.getId());
        } catch (RuntimeException e) {
            return Uni.createFrom().failure(new IllegalStateException(
                    "Failed to parse two-speaker dialogue JSON for scene '" + sceneTitle + "': " + e.getMessage(), e));
        }
        if (segments.isEmpty()) {
            return Uni.createFrom().failure(new IllegalStateException(
                    "Two-speaker dialogue produced no turns for scene: " + sceneTitle));
        }

        return introTtsGenerator.generateDialogueAudio(segments, sceneTitle, traceId, slug);
    }

    private List<ElevenLabsClient.DialogueSegment> parseDialogueJson(String text, String speaker1VoiceId, String speaker2VoiceId) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalStateException("no JSON object found in model output");
        }
        JsonObject root = new JsonObject(text.substring(start, end + 1));
        JsonArray inputs = root.getJsonArray("inputs");
        if (inputs == null) {
            throw new IllegalStateException("JSON output missing 'inputs' array");
        }

        List<ElevenLabsClient.DialogueSegment> segments = new ArrayList<>();
        for (int i = 0; i < inputs.size(); i++) {
            JsonObject turn = inputs.getJsonObject(i);
            String ref = turn.getString("voice_id");
            String line = turn.getString("text");
            String voiceId = switch (ref == null ? "" : ref) {
                case SPEAKER_1_REF -> speaker1VoiceId;
                case SPEAKER_2_REF -> speaker2VoiceId;
                default -> throw new IllegalStateException("unknown voice placeholder: " + ref);
            };
            segments.add(new ElevenLabsClient.DialogueSegment(voiceId, line));
        }
        return segments;
    }
}
