package com.semantyca.jesoos.service.chat;

import com.semantyca.core.model.user.IUser;
import com.semantyca.core.util.FileSecurityUtils;
import com.semantyca.jesoos.config.JesoosConfig;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.core.buffer.Buffer;
import io.vertx.mutiny.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Downloads a track from a Suno share link into the same temp upload directory that
 * {@code /jesoos/chat/upload-temp} writes to, so the returned filename can be fed straight
 * into {@code upload_song} (see CHAT_WORKFLOW §5). Suno serves the rendered track from its
 * CDN as {@code https://cdn1.suno.ai/<songId>.mp3}; we resolve the song id from the link and
 * pull that cached file.
 */
@ApplicationScoped
public class SunoImportService {

    private static final Logger LOGGER = Logger.getLogger(SunoImportService.class);
    private static final String UPLOAD_CONTROLLER = "chat-upload-controller";
    private static final String CDN_TEMPLATE = "https://cdn1.suno.ai/%s.mp3";
    // Suno song ids are UUIDs; they appear in /song/<id>, /s/<id> or directly in the CDN url.
    private static final Pattern SONG_ID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    @Inject
    JesoosConfig config;

    @Inject
    Vertx vertx;

    private WebClient webClient;

    @PostConstruct
    void init() {
        this.webClient = WebClient.create(vertx, new WebClientOptions().setFollowRedirects(true));
    }

    /**
     * @return the temp basename to pass as {@code temp_filename} to {@code upload_song}.
     */
    public Uni<String> downloadToTemp(String sunoUrl, IUser user) {
        return resolveSongId(sunoUrl)
                .chain(songId -> {
                    if (songId == null) {
                        return Uni.createFrom().failure(new IllegalArgumentException("Could not read a Suno song id from the link"));
                    }
                    String cdnUrl = String.format(CDN_TEMPLATE, songId);
                    String uniqueFilename = "suno-" + songId + "-" + UUID.randomUUID().toString().substring(0, 8) + ".mp3";

                    return webClient.getAbs(cdnUrl).send()
                            .map(response -> {
                                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                                    throw new RuntimeException("Suno CDN returned HTTP " + response.statusCode() + " for " + cdnUrl);
                                }
                                Buffer body = response.body();
                                if (body == null || body.length() == 0) {
                                    throw new RuntimeException("Suno CDN returned an empty file for " + cdnUrl);
                                }
                                return body.getBytes();
                            })
                            .emitOn(Infrastructure.getDefaultWorkerPool())
                            .map(bytes -> writeTemp(user, uniqueFilename, bytes));
                });
    }

    /**
     * The song id is a UUID. Full song links ({@code /song/<uuid>}) and CDN urls contain it directly;
     * short links ({@code /s/<code>}) 307-redirect to {@code /song/<uuid>?sh=...}, so when the url
     * itself has no UUID we resolve one redirect hop and read it from the {@code Location} header.
     */
    private Uni<String> resolveSongId(String sunoUrl) {
        String direct = extractSongId(sunoUrl);
        if (direct != null) return Uni.createFrom().item(direct);
        if (sunoUrl == null || sunoUrl.isBlank()) return Uni.createFrom().nullItem();
        return webClient.getAbs(sunoUrl).followRedirects(false).send()
                .map(response -> {
                    String location = response.getHeader("location");
                    return location == null ? null : extractSongId(location);
                })
                .onFailure().recoverWithItem((String) null);
    }

    private String writeTemp(IUser user, String uniqueFilename, byte[] bytes) {
        Path destDir = Paths.get(config.getPathUploads(), UPLOAD_CONTROLLER, user.getLogin(), "temp");
        try {
            Files.createDirectories(destDir);
            Path destFile = FileSecurityUtils.secureResolve(destDir, uniqueFilename);
            Path tmp = Files.createTempFile(destDir, "suno-", ".part");
            Files.write(tmp, bytes);
            Files.move(tmp, destFile, StandardCopyOption.REPLACE_EXISTING);
            LOGGER.infof("Suno import saved: %s (%d bytes) for user %s", uniqueFilename, bytes.length, user.getLogin());
            return uniqueFilename;
        } catch (Exception e) {
            throw new RuntimeException("Failed to save Suno track for user " + user.getLogin() + ": " + e.getMessage(), e);
        }
    }

    private static String extractSongId(String sunoUrl) {
        if (sunoUrl == null) return null;
        Matcher m = SONG_ID.matcher(sunoUrl);
        return m.find() ? m.group() : null;
    }
}
