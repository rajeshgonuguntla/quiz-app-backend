package com.codapt.quizapp.service.impl;

import com.codapt.quizapp.dto.YoutubeCaptionDetails;
import com.codapt.quizapp.service.YoutubeCaptionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;


@Service
public class YoutubeCaptionServiceImpl implements YoutubeCaptionService {

    private static final Logger logger = LoggerFactory.getLogger(YoutubeCaptionServiceImpl.class);
    private final String proxyUser = System.getenv("PROXY_USER");
    private final String proxyPass = System.getenv("PROXY_PASS");
    private final String proxyHost = defaultIfBlank(System.getenv("PROXY_HOST"), "gate.decodo.com");
    private final String proxyPort = defaultIfBlank(System.getenv("PROXY_PORT"), "7000");

    @Override
    public YoutubeCaptionDetails downloadCaptions(String youtubeUrl) throws Exception {
        logger.info("Starting caption download for YouTube URL: {}", youtubeUrl);

        // Determine the yt-dlp command to use. In Docker the image installs yt-dlp on PATH.
        String ytDlpCmd = trimToNull(System.getenv("YTDLP_CMD"));
        if (ytDlpCmd == null) {
            ytDlpCmd = "yt-dlp";
        }

        // Build proxy URL dynamically. Priority:
        // 1) Explicit PROXY_URL env var
        // 2) PROXY_USER/PROXY_PASS/PROXY_HOST/PROXY_PORT (or use webshare* defaults defined above)
        String proxyUrl = null;
        String explicitProxy = trimToNull(System.getenv("PROXY_URL"));
        if (explicitProxy != null) {
            proxyUrl = explicitProxy;
        } else {
            String proxyHost = trimToNull(this.proxyHost);
            String proxyPort = trimToNull(this.proxyPort);
            String proxyUser = trimToNull(this.proxyUser);
            String proxyPass = trimToNull(this.proxyPass);

            if (proxyHost != null && proxyPort != null) {
                StringBuilder sb = new StringBuilder();
                sb.append("http://");
                if (proxyUser != null && proxyPass != null) {
                    try {
                        sb.append(URLEncoder.encode(proxyUser, StandardCharsets.UTF_8.name()))
                                .append(":")
                                .append(URLEncoder.encode(proxyPass, StandardCharsets.UTF_8.name()))
                                .append("@");
                    } catch (UnsupportedEncodingException e) {
                        // UTF-8 should always be available; fall back to raw values if it isn't
                        sb.append(proxyUser).append(":").append(proxyPass).append("@");
                    }
                }
                sb.append(proxyHost).append(":").append(proxyPort);
                proxyUrl = sb.toString();
            }
        }
       // logger.info("Proxy URL: {}", proxyUrl);

        YtDlpExecutionResult result;

        logger.info("proxyUrl: {}", proxyUrl);

        if (proxyUrl != null) {
            logger.info("Using proxy for yt-dlp request");
            result = executeYtDlp(buildYtDlpCommand(ytDlpCmd, youtubeUrl, proxyUrl));
            if (result.exitCode() != 0) {
                if (logger.isWarnEnabled()) {
                    logger.warn("yt-dlp failed via proxy (exit code {}). Retrying without proxy once. Output: {}",
                            result.exitCode(), summarizeYtDlpOutput(result.stderr()));
                }
               // result = executeYtDlp(buildYtDlpCommand(ytDlpCmd, youtubeUrl, null));
            }
        } else {
            logger.warn("No proxy configured for yt-dlp. Direct request will be attempted.");
            result = executeYtDlp(buildYtDlpCommand(ytDlpCmd, youtubeUrl, null));
        }

        if (result.exitCode() != 0) {
            if (logger.isErrorEnabled()) {
                logger.error("yt-dlp failed with exit code {}. Output: {}", result.exitCode(), summarizeYtDlpOutput(result.stderr()));
            }
            throw new IllegalStateException("Failed to retrieve video information from yt-dlp (exit=" + result.exitCode() + ")");
        }

        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(extractJsonPayload(result.stdout()));

      //  logger.info("JSON Payload: {}", root.toString());

        String videoTitle = root.path("title").asText("Unknown title");
        String channelName = root.path("channel").asText("Unknown channel");
        long durationSeconds = root.path("duration").asLong(0);
        String videoLength = formatDuration(durationSeconds);

        logger.info("Fetched YouTube metadata - title: '{}', channel: '{}', length: {}",
                videoTitle, channelName, videoLength);

        SubtitleSelection subtitleSelection = selectEnglishSubtitle(root);
        String subtitleUrl = subtitleSelection.url();

        String cleanedCaptions = null;

        if (subtitleUrl == null) {
            logger.warn("No English subtitle URL found in yt-dlp metadata for video: {}", youtubeUrl);
        } else {
            logger.info("Using {} captions, downloading from: {}", subtitleSelection.source(), subtitleUrl);

            StringBuilder captions = new StringBuilder();
            try (BufferedReader subtitleReader = new BufferedReader(
                    new InputStreamReader(URI.create(subtitleUrl).toURL().openStream()))) {
                String line;
                while ((line = subtitleReader.readLine()) != null) {
                    captions.append(line).append("\n");
                }
            }

            logger.info("Captions downloaded successfully for video: {}", youtubeUrl);
            cleanedCaptions = cleanCaptionText(captions.toString());
        }

        if (cleanedCaptions == null || cleanedCaptions.isBlank()) {
            logger.warn("No captions found for video: {}", youtubeUrl);
            return new YoutubeCaptionDetails(
                    "No captions available for this video",
                    videoTitle,
                    channelName,
                    videoLength
            );
        }

        return new YoutubeCaptionDetails(cleanedCaptions, videoTitle, channelName, videoLength);
    }

    SubtitleSelection selectEnglishSubtitle(JsonNode root) {
        String manualSubtitleUrl = extractSubtitleUrlForLanguage(root.path("subtitles"), "en");
        if (manualSubtitleUrl != null) {
            return new SubtitleSelection(manualSubtitleUrl, "manual");
        }

        String automaticSubtitleUrl = extractSubtitleUrlForLanguage(root.path("automatic_captions"), "en");
        if (automaticSubtitleUrl != null) {
            return new SubtitleSelection(automaticSubtitleUrl, "automatic");
        }

        return SubtitleSelection.none();
    }

    private String extractSubtitleUrlForLanguage(JsonNode captionRoot, String languagePrefix) {
        if (captionRoot == null || !captionRoot.isObject()) {
            return null;
        }

        JsonNode exactLanguage = captionRoot.path(languagePrefix);
        String exactLanguageUrl = firstUrlFromTrackList(exactLanguage);
        if (exactLanguageUrl != null) {
            return exactLanguageUrl;
        }

        Iterator<Map.Entry<String, JsonNode>> fields = captionRoot.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            if (entry.getKey() != null && entry.getKey().startsWith(languagePrefix)) {
                String url = firstUrlFromTrackList(entry.getValue());
                if (url != null) {
                    return url;
                }
            }
        }

        return null;
    }

    private String firstUrlFromTrackList(JsonNode trackList) {
        if (!trackList.isArray()) {
            return null;
        }

        for (JsonNode track : trackList) {
            String url = trimToNull(track.path("url").asText(null));
            if (url != null) {
                return url;
            }
        }

        return null;
    }

    private String cleanCaptionText(String captionText) {
        if (captionText == null) {
            return null;
        }

        return captionText
                .replace("WEBVTT", "")
                .replaceAll("\\d{2}:\\d{2}:\\d{2}\\.\\d+ --> .*", "")
                .replaceAll("<[^>]*+>", "")
                .trim();
    }


    private String defaultIfBlank(String value, String defaultValue) {
        String normalized = trimToNull(value);
        return normalized == null ? defaultValue : normalized;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }

        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private List<String> buildYtDlpCommand(String ytDlpCmd, String youtubeUrl, String proxyUrl) {
        List<String> command = new ArrayList<>();
        command.add(ytDlpCmd);
        command.add("-N"); // Use up to 5 concurrent connections for faster metadata retrieval
        command.add("4");
        if (proxyUrl != null) {
            command.add("--proxy");
            command.add(proxyUrl);
        }
        command.add("--write-subs");
        command.add("--write-auto-subs");
        command.add("--skip-download");
        command.add("--dump-json");
        command.add(youtubeUrl);
        return command;
    }

    private YtDlpExecutionResult executeYtDlp(List<String> command) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);

        Process process = pb.start();

        CompletableFuture<String> stdoutFuture = CompletableFuture.supplyAsync(() -> readStream(process.getInputStream()));
        CompletableFuture<String> stderrFuture = CompletableFuture.supplyAsync(() -> readStream(process.getErrorStream()));

        int exitCode = process.waitFor();
        try {
            String stdout = stdoutFuture.get();
            String stderr = stderrFuture.get();
            return new YtDlpExecutionResult(exitCode, stdout, stderr);
        } catch (Exception ex) {
            logger.warn("Error reading yt-dlp streams", ex);
            return new YtDlpExecutionResult(exitCode, "", "");
        }
    }

    private String readStream(InputStream inputStream) {
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        } catch (Exception ex) {
            logger.warn("Failed to read process stream", ex);
        }
        return output.toString();
    }

    private String extractJsonPayload(String stdout) {
        if (stdout == null || stdout.isBlank()) {
            throw new IllegalStateException("yt-dlp did not produce JSON output");
        }

        String trimmed = stdout.trim();
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return trimmed;
        }

        for (String line : trimmed.split("\\R")) {
            String candidate = line.trim();
            if (candidate.startsWith("{") && candidate.endsWith("}")) {
                return candidate;
            }
        }

        throw new IllegalStateException("yt-dlp output did not contain JSON payload");
    }

    private String summarizeYtDlpOutput(String output) {
        if (output == null) {
            return "<empty>";
        }

        String normalized = output.trim();
        if (normalized.isEmpty()) {
            return "<empty>";
        }

        int maxLength = 500;
        if (normalized.length() <= maxLength) {
            return normalized;
        }

        return normalized.substring(0, maxLength) + "...";
    }

    private record YtDlpExecutionResult(int exitCode, String stdout, String stderr) {
    }

    private String formatDuration(long durationSeconds) {
        if (durationSeconds <= 0) {
            return "00:00";
        }

        long hours = durationSeconds / 3600;
        long minutes = (durationSeconds % 3600) / 60;
        long seconds = durationSeconds % 60;

        if (hours > 0) {
            return String.format("%02d:%02d:%02d", hours, minutes, seconds);
        }

        return String.format("%02d:%02d", minutes, seconds);
    }

    record SubtitleSelection(String url, String source) {
        private static SubtitleSelection none() {
            return new SubtitleSelection(null, "none");
        }
    }
}
