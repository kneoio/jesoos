package com.semantyca.jesoos.external;

import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.texttospeech.v1beta1.*;
import com.google.protobuf.ByteString;
import com.semantyca.core.model.cnst.LanguageTag;
import com.semantyca.jesoos.config.JesoosConfig;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.FileInputStream;
import java.io.IOException;

@ApplicationScoped
public class GCPTTSClient implements TTSClient {
    private static final Logger LOGGER = Logger.getLogger(GCPTTSClient.class);
    private static final int MAX_TEXT_LENGTH = 3000;
    private static final double VOLUME_GAIN_DB = 6.0;

    @Inject
    JesoosConfig config;

    private TextToSpeechClient gcpClient;

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

        this.gcpClient = TextToSpeechClient.create(settings);
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

            LOGGER.infof("GCP TTS using voice=%s lang=%s", voiceId, langCode);

            AudioConfig audioConfig = AudioConfig.newBuilder()
                    .setAudioEncoding(AudioEncoding.MP3)
                    .setVolumeGainDb(VOLUME_GAIN_DB)
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
                LOGGER.errorf("GCP TTS generation failed: %s", e.getMessage());
                throw new RuntimeException("GCP TTS generation failed: " + e.getMessage(), e);
            }
        });
    }

    private static String getLanguageCode(LanguageTag tag) {
        return switch (tag) {
            // most used
            case EN_US, EN_GB, EN_AU, EN_IN -> "en-US";
            case ES_ES, ES_US           -> "es-ES";
            case FR_FR, FR_CA           -> "fr-FR";
            case PT_PT, PT_PT_ALT       -> "pt-PT";
            case PT_BR                  -> "pt-BR";
            case DE_DE                  -> "de-DE";
            case HI_IN                  -> "hi-IN";
            case TR_TR                  -> "tr-TR";
            case AR_XA                  -> "ar-XA";
            case CMN_CN, ZH_CN          -> "cmn-CN";
            case BG_BG                  -> "bg-BG";
            case LV_LV                  -> "lv-LV";
            case SV_SE                  -> "sv-SE";
            case IT_IT                  -> "it-IT";
            case JA_JP                  -> "ja-JP";
            case KO_KR                  -> "ko-KR";
            case FI_FI                  -> "fi-FI";
            case TH_TH                  -> "th-TH";
            case UK_UA                  -> "uk-UA";
            case RU_RU                  -> "ru-RU";
            // others
            case NL_NL, NL_BE           -> "nl-NL";
            case DA_DK                  -> "da-DK";
            case NO_NO, NB_NO           -> "nb-NO";
            case PL_PL                  -> "pl-PL";
            case CS_CZ                  -> "cs-CZ";
            case SK_SK                  -> "sk-SK";
            case HR_HR                  -> "hr-HR";
            case SL_SI                  -> "sl-SI";
            case SR_RS                  -> "sr-RS";
            case RO_RO                  -> "ro-RO";
            case EL_GR                  -> "el-GR";
            case HU_HU                  -> "hu-HU";
            case LT_LT                  -> "lt-LT";
            case ET_EE                  -> "et-EE";
            case KK_KZ                  -> "kk-KZ";
            case KA_GE                  -> "ka-GE";
            case CMN_TW                 -> "cmn-TW";
            case YUE_HK                 -> "yue-HK";
            case BN_IN                  -> "bn-IN";
            case GU_IN                  -> "gu-IN";
            case KN_IN                  -> "kn-IN";
            case ML_IN                  -> "ml-IN";
            case MR_IN                  -> "mr-IN";
            case PA_IN                  -> "pa-IN";
            case TA_IN                  -> "ta-IN";
            case TE_IN                  -> "te-IN";
            case UR_IN                  -> "ur-IN";
            case ID_ID                  -> "id-ID";
            case MS_MY                  -> "ms-MY";
            case FIL_PH                 -> "fil-PH";
            case VI_VN                  -> "vi-VN";
            case HE_IL                  -> "he-IL";
            case AM_ET                  -> "am-ET";
            case SW_KE                  -> "sw-KE";
        };
    }
}