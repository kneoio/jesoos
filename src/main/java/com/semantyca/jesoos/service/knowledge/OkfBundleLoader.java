package com.semantyca.jesoos.service.knowledge;

import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads an OKF bundle from the classpath by walking its {@code index.md} files. The spec makes
 * index files the directory listing, which doubles as the manifest a classpath reader needs — no
 * directory scanning, so a bundle works the same from a source tree or a packaged jar.
 */
public final class OkfBundleLoader {

    private static final Logger LOG = Logger.getLogger(OkfBundleLoader.class);
    private static final Pattern MARKDOWN_LINK = Pattern.compile("\\[[^]]*]\\(([^)\\s]+)\\)");
    private static final String INDEX = "index.md";
    private static final String LOG_FILE = "log.md";

    private OkfBundleLoader() {}

    public static List<OkfConcept> load(String bundleRoot) {
        String root = bundleRoot.endsWith("/") ? bundleRoot.substring(0, bundleRoot.length() - 1) : bundleRoot;
        List<OkfConcept> concepts = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        loadDirectory(root, concepts, visited);
        if (concepts.isEmpty()) {
            LOG.warnf("[okf] bundle '%s' produced no concepts", root);
        }
        return List.copyOf(concepts);
    }

    private static void loadDirectory(String directory, List<OkfConcept> concepts, Set<String> visited) {
        if (!visited.add(directory)) return;

        String index = readResource(directory + "/" + INDEX);
        if (index == null) {
            LOG.warnf("[okf] missing %s in '%s'", INDEX, directory);
            return;
        }

        Matcher matcher = MARKDOWN_LINK.matcher(index);
        while (matcher.find()) {
            String target = matcher.group(1);
            if (target.contains("://") || target.startsWith("/") || target.startsWith("#")) continue;

            if (target.endsWith("/")) {
                loadDirectory(directory + "/" + target.substring(0, target.length() - 1), concepts, visited);
                continue;
            }
            if (!target.endsWith(".md") || target.endsWith(INDEX) || target.endsWith(LOG_FILE)) continue;

            String path = directory + "/" + target;
            if (!visited.add(path)) continue;

            OkfConcept concept = OkfConcept.parse(path, readResource(path));
            if (concept == null) {
                LOG.warnf("[okf] skipped '%s' — missing or malformed frontmatter", path);
                continue;
            }
            concepts.add(concept);
        }
    }

    private static String readResource(String path) {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) loader = OkfBundleLoader.class.getClassLoader();
        try (InputStream in = loader.getResourceAsStream(path)) {
            if (in == null) return null;
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.warnf("[okf] failed to read '%s': %s", path, e.getMessage());
            return null;
        }
    }
}
