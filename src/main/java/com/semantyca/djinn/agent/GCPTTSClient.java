package com.semantyca.djinn.agent;

import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.texttospeech.v1beta1.AudioConfig;
import com.google.cloud.texttospeech.v1beta1.AudioEncoding;
import com.google.cloud.texttospeech.v1beta1.SynthesisInput;
import com.google.cloud.texttospeech.v1beta1.SynthesizeSpeechResponse;
import com.google.cloud.texttospeech.v1beta1.TextToSpeechSettings;
import com.google.cloud.texttospeech.v1beta1.VoiceSelectionParams;
import com.google.protobuf.ByteString;
import com.semantyca.core.model.cnst.LanguageTag;
import com.semantyca.djinn.config.BroadcasterConfig;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.IOException;

@ApplicationScoped
public class GCPTTSClient implements TextToSpeechClient {
    private static final Logger LOGGER = LoggerFactory.getLogger(GCPTTSClient.class);
    private static final int MAX_TEXT_LENGTH = 3000;

    @Inject
    BroadcasterConfig config;

    private com.google.cloud.texttospeech.v1beta1.TextToSpeechClient gcpClient;

    @PostConstruct
    void init() throws IOException {
        String credentialsPath = config.getGcpCredentialsPath();

        if (credentialsPath == null || credentialsPath.isEmpty()) {
            throw new IllegalArgumentException("GCP TTS credentials_path is required");
        }

        GoogleCredentials credentials = GoogleCredentials.fromStream(new FileInputStream(credentialsPath));
        TextToSpeechSettings settings = TextToSpeechSettings.newBuilder()
                .setCredentialsProvider(FixedCredentialsProvider.create(credentials))
                .build();

        this.gcpClient = com.google.cloud.texttospeech.v1beta1.TextToSpeechClient.create(settings);
    }

    @Override
    public Uni<byte[]> textToSpeech(String text, String voiceId, String modelId, LanguageTag languageTag) {
        return Uni.createFrom().item(() -> {
            if (text == null || text.isEmpty()) {
                throw new IllegalArgumentException("No text provided for TTS");
            }

            String langCode = getLanguageCode(languageTag);

            VoiceSelectionParams voice = VoiceSelectionParams.newBuilder()
                    .setLanguageCode(langCode)
                    .setName(voiceId)
                    .build();

            LOGGER.info("GCP TTS using voice={} lang={}", voiceId, langCode);

            AudioConfig audioConfig = AudioConfig.newBuilder()
                    .setAudioEncoding(AudioEncoding.MP3)
                    .build();

            String truncatedText = text.length() > MAX_TEXT_LENGTH
                    ? text.substring(0, MAX_TEXT_LENGTH)
                    : text;

            SynthesisInput synthesisInput = SynthesisInput.newBuilder()
                    .setText(truncatedText)
                    .build();

            try {
                SynthesizeSpeechResponse response = gcpClient.synthesizeSpeech(synthesisInput, voice, audioConfig);
                ByteString audioContent = response.getAudioContent();

                if (audioContent.isEmpty()) {
                    throw new RuntimeException("GCP TTS conversion resulted in empty audio");
                }
                return audioContent.toByteArray();

            } catch (Exception e) {
                LOGGER.error("GCP TTS generation failed: {}", e.getMessage());
                throw new RuntimeException("GCP TTS generation failed: " + e.getMessage(), e);
            }
        });
    }

    private static String getLanguageCode(LanguageTag tag) {
        return switch (tag) {
            case EN_GB -> "en-GB";
            case ES_ES -> "es-ES";
            case JA_JP -> "ja-JP";
            case ZH_CN -> "zh-CN";
            case FR_FR -> "fr-FR";
            case PT_BR -> "pt-BR";
            case HI_IN -> "hi-IN";
            case IT_IT -> "it-IT";
            case UK_UA -> "uk-UA";
            default -> "en-US";
        };
    }
}