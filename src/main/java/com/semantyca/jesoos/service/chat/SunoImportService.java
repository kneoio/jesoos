package com.semantyca.jesoos.service.chat;

import com.semantyca.core.model.user.IUser;
import com.semantyca.core.util.FileSecurityUtils;
import com.semantyca.jesoos.config.JesoosConfig;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.vertx.core.json.JsonObject;
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
 *
 * <p>In addition to the audio, we fetch the public song page and scrape the embedded Next.js
 * RSC payload for title / artist / genre tags / artwork so the chat agent can confirm the
 * metadata with the artist instead of asking for everything from scratch. Metadata scraping
 * is strictly best-effort: any failure degrades to {@link SunoTrackMetadata#empty} and the
 * download still succeeds.
 */
@ApplicationScoped
public class SunoImportService {

    private static final Logger LOGGER = Logger.getLogger(SunoImportService.class);
    private static final String UPLOAD_CONTROLLER = "chat-upload-controller";
    private static final String CDN_TEMPLATE = "https://cdn1.suno.ai/%s.mp3";
    private static final String SONG_PAGE_TEMPLATE = "https://suno.com/song/%s";
    // Suno returns 403 for the default WebClient UA; a browser UA gets the rendered HTML.
    private static final String BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    // Suno song ids are UUIDs; they appear in /song/<id>, /s/<id> or directly in the CDN url.
    private static final Pattern SONG_ID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    // The song object is streamed inside a `self.__next_f.push([1,"<escaped-json>"])` chunk;
    // the one carrying the track carries "audio_url". We unescape it then pull individual fields.
    private static final Pattern NEXT_F_CHUNK =
            Pattern.compile("self\\.__next_f\\.push\\(\\[1,\"(.*?)\"\\]\\)", Pattern.DOTALL);
    private static final Pattern TITLE = Pattern.compile("\"title\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern DISPLAY_NAME = Pattern.compile("\"display_name\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern HANDLE = Pattern.compile("\"handle\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern IMAGE_URL = Pattern.compile("\"image_url\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern DISPLAY_TAGS = Pattern.compile("\"display_tags\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern DURATION = Pattern.compile("\"duration\"\\s*:\\s*([0-9.]+)");
    private static final Pattern PROMPT =
            Pattern.compile("\"metadata\"\\s*:\\s*\\{[^}]*?\"prompt\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern TITLE_TAG = Pattern.compile("<title>(.*?)</title>", Pattern.DOTALL);

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
     * Downloads the track and scrapes its metadata.
     *
     * @return the temp basename (in {@link SunoTrackMetadata#tempFilename}) to pass as
     * {@code temp_filename} to {@code upload_song}, plus any metadata that could be scraped.
     */
    public Uni<SunoTrackMetadata> downloadToTemp(String sunoUrl, IUser user) {
        return resolveSongId(sunoUrl)
                .chain(songId -> {
                    if (songId == null) {
                        return Uni.createFrom().failure(new IllegalArgumentException("Could not read a Suno song id from the link"));
                    }
                    Uni<String> fileUni = downloadMp3(songId, user);
                    Uni<SunoTrackMetadata> metaUni = fetchMetadata(songId);
                    return Uni.combine().all().unis(fileUni, metaUni).asTuple()
                            .map(tuple -> tuple.getItem2().withTempFilename(tuple.getItem1()));
                });
    }

    private Uni<String> downloadMp3(String songId, IUser user) {
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
    }

    /**
     * Best-effort scrape of the public song page. Never fails the import: on any error (403, timeout,
     * markup change) it recovers to {@link SunoTrackMetadata#empty} so the download still returns.
     */
    private Uni<SunoTrackMetadata> fetchMetadata(String songId) {
        String pageUrl = String.format(SONG_PAGE_TEMPLATE, songId);
        return webClient.getAbs(pageUrl)
                .putHeader("User-Agent", BROWSER_UA)
                .send()
                .map(response -> {
                    String html = response.bodyAsString();
                    return html == null ? SunoTrackMetadata.empty(songId) : parseMetadata(html, songId);
                })
                .onFailure().recoverWithItem(err -> {
                    LOGGER.warnf("Suno metadata scrape failed for %s: %s", songId, err.getMessage());
                    return SunoTrackMetadata.empty(songId);
                });
    }

    /**
     * The song object is JSON embedded in a JS-string-escaped RSC chunk; nested strings such as
     * {@code metadata.prompt} are double-encoded, so we JSON-unescape the chunk once, then unescape
     * the prompt substring a second time. Using JSON unescape (not a byte decode) keeps non-ASCII
     * text — e.g. Cyrillic titles — intact.
     */
    static SunoTrackMetadata parseMetadata(String html, String songId) {
        String title = null, artist = null, handle = null, genreTags = null, imageUrl = null, prompt = null;
        Double duration = null;

        String chunk = null;
        Matcher cm = NEXT_F_CHUNK.matcher(html);
        while (cm.find()) {
            String raw = cm.group(1);
            if (raw.contains("audio_url")) {
                chunk = raw;
                break;
            }
        }

        if (chunk != null) {
            String text = jsonUnescape(chunk);
            if (text != null) {
                title = firstGroup(text, TITLE);
                artist = firstGroup(text, DISPLAY_NAME);
                handle = firstGroup(text, HANDLE);
                imageUrl = firstGroup(text, IMAGE_URL);
                genreTags = firstGroup(text, DISPLAY_TAGS);
                String durStr = firstGroup(text, DURATION);
                if (durStr != null) {
                    try {
                        duration = Double.parseDouble(durStr);
                    } catch (NumberFormatException ignore) {
                        // leave null
                    }
                }
                String rawPrompt = firstGroup(text, PROMPT);
                if (rawPrompt != null) {
                    prompt = jsonUnescape(rawPrompt); // double-encoded relative to the chunk
                }
            }
        }

        if (title == null) {
            String t = firstGroup(html, TITLE_TAG);
            if (t != null) title = t.trim();
        }

        return new SunoTrackMetadata(songId, null, title, artist, handle, genreTags, imageUrl, duration, prompt);
    }

    /**
     * JSON-unescapes a JS-string body by embedding it as a value and reading it back. Equivalent to
     * {@code json.loads('"' + s + '"')} and, unlike a latin-1/byte decode, leaves non-ASCII text intact.
     */
    private static String jsonUnescape(String escaped) {
        try {
            return new JsonObject("{\"v\":\"" + escaped + "\"}").getString("v");
        } catch (Exception e) {
            return null;
        }
    }

    private static String firstGroup(String s, Pattern p) {
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1) : null;
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
