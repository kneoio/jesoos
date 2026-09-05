package com.semantyca.jesoos.external;

import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.speech.v1.RecognitionAudio;
import com.google.cloud.speech.v1.RecognitionConfig;
import com.google.cloud.speech.v1.RecognitionConfig.AudioEncoding;
import com.google.cloud.speech.v1.RecognizeResponse;
import com.google.cloud.speech.v1.SpeechClient;
import com.google.cloud.speech.v1.SpeechRecognitionAlternative;
import com.google.cloud.speech.v1.SpeechRecognitionResult;
import com.google.cloud.speech.v1.SpeechSettings;
import com.google.protobuf.ByteString;
import com.semantyca.core.model.cnst.LanguageTag;
import com.semantyca.jesoos.config.JesoosConfig;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

@ApplicationScoped
public class GCPSTTClient implements STTClient {
    private static final Logger LOGGER = Logger.getLogger(GCPSTTClient.class);
    private static final int MAX_SYNC_BYTES = 10 * 1024 * 1024;
    private static final int MAX_LANGUAGE_CODES = 4;
    private static final List<LanguageTag> DEFAULT_HINTS = List.of(
            LanguageTag.EN_US, LanguageTag.ES_ES, LanguageTag.PT_PT, LanguageTag.RU_RU);

    @Inject
    JesoosConfig config;

    private SpeechClient speechClient;

    @PostConstruct
    void init() throws IOException {
        String credentialsPath = config.getGcpCredentialsPath();

        if (credentialsPath == null || credentialsPath.isEmpty()) {
            throw new IllegalArgumentException("GCP STT credentials_path is required");
        }

        GoogleCredentials credentials = GoogleCredentials.fromStream(new FileInputStream(credentialsPath));
        SpeechSettings settings = SpeechSettings.newBuilder()
                .setCredentialsProvider(FixedCredentialsProvider.create(credentials))
                .build();

        this.speechClient = SpeechClient.create(settings);
    }

    @Override
    public Uni<SttResult> transcribe(Path audioFile, List<LanguageTag> languageHints) {
        return Uni.createFrom().item(() -> {
            if (audioFile == null || !Files.isRegularFile(audioFile)) {
                return SttResult.failure("audio file is missing");
            }
            try {
                byte[] bytes = Files.readAllBytes(audioFile);
                if (bytes.length == 0) {
                    return SttResult.failure("audio file is empty");
                }
                if (bytes.length > MAX_SYNC_BYTES) {
                    return SttResult.failure("audio file exceeds 10 MB sync STT limit");
                }

                List<String> langCodes = languageCodes(languageHints);
                String primary = langCodes.getFirst();
                AudioEncoding encoding = encodingFor(audioFile);
                LOGGER.infof("GCP STT file=%s langs=%s encoding=%s bytes=%d",
                        audioFile.getFileName(), langCodes, encoding, bytes.length);

                RecognitionConfig.Builder configBuilder = RecognitionConfig.newBuilder()
                        .setEncoding(encoding)
                        .setLanguageCode(primary)
                        .setEnableAutomaticPunctuation(true)
                        .setModel("latest_short");
                for (int i = 1; i < langCodes.size(); i++) {
                    configBuilder.addAlternativeLanguageCodes(langCodes.get(i));
                }
                if (encoding == AudioEncoding.LINEAR16) {
                    configBuilder.setSampleRateHertz(16000);
                }

                RecognitionAudio audio = RecognitionAudio.newBuilder()
                        .setContent(ByteString.copyFrom(bytes))
                        .build();

                RecognizeResponse response = speechClient.recognize(configBuilder.build(), audio);
                if (response.getResultsCount() == 0) {
                    return SttResult.failure("no speech recognized");
                }

                String transcript = response.getResultsList().stream()
                        .filter(r -> r.getAlternativesCount() > 0)
                        .map(r -> r.getAlternatives(0).getTranscript().trim())
                        .filter(t -> !t.isEmpty())
                        .collect(Collectors.joining(" "));
                if (transcript.isBlank()) {
                    return SttResult.failure("no speech recognized");
                }

                SpeechRecognitionResult first = response.getResults(0);
                SpeechRecognitionAlternative alt = first.getAlternatives(0);
                float confidence = alt.getConfidence();
                String detected = first.getLanguageCode();
                if (detected == null || detected.isBlank()) {
                    detected = primary;
                }
                LOGGER.infof("GCP STT transcript length=%d confidence=%.2f lang=%s",
                        transcript.length(), confidence, detected);
                return SttResult.success(transcript, confidence, detected);
            } catch (Exception e) {
                LOGGER.errorf("GCP STT failed: %s", e.getMessage());
                return SttResult.failure("GCP STT failed: " + e.getMessage());
            }
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    static List<String> languageCodes(List<LanguageTag> hints) {
        List<String> codes = new ArrayList<>();
        if (hints != null) {
            for (LanguageTag tag : hints) {
                addCode(codes, languageCode(tag));
            }
        }
        for (LanguageTag tag : DEFAULT_HINTS) {
            addCode(codes, languageCode(tag));
        }
        if (codes.isEmpty()) {
            codes.add(LanguageTag.EN_US.tag());
        }
        if (codes.size() > MAX_LANGUAGE_CODES) {
            return List.copyOf(codes.subList(0, MAX_LANGUAGE_CODES));
        }
        return List.copyOf(codes);
    }

    private static void addCode(List<String> codes, String code) {
        if (code != null && !code.isBlank() && !codes.contains(code) && codes.size() < MAX_LANGUAGE_CODES) {
            codes.add(code);
        }
    }

    static String languageCode(LanguageTag tag) {
        if (tag == null) {
            return LanguageTag.EN_US.tag();
        }
        return switch (tag) {
            case CMN_CN, ZH_CN -> "zh-CN";
            case CMN_TW -> "zh-TW";
            case YUE_HK -> "yue-Hant-HK";
            case AR_XA -> "ar";
            default -> tag.tag();
        };
    }

    static AudioEncoding encodingFor(Path audioFile) {
        String name = audioFile.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        String ext = dot >= 0 ? name.substring(dot + 1) : "";
        return switch (ext) {
            case "wav" -> AudioEncoding.LINEAR16;
            case "flac" -> AudioEncoding.FLAC;
            case "mp3" -> AudioEncoding.MP3;
            case "ogg", "opus" -> AudioEncoding.OGG_OPUS;
            case "webm", "weba" -> AudioEncoding.WEBM_OPUS;
            default -> AudioEncoding.WEBM_OPUS;
        };
    }
}
