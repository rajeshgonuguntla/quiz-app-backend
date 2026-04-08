package com.codapt.quizapp.service.impl;

import com.codapt.quizapp.dto.YoutubeCaptionDetails;
import com.codapt.quizapp.service.YoutubeCaptionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.thoroldvix.api.TranscriptContent;
import io.github.thoroldvix.api.TranscriptApiFactory;
import io.github.thoroldvix.api.YoutubeTranscriptApi;
// ...existing code...
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
// ...existing code...
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.*;
// ...existing code...
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;


@Service
public class YoutubeCaptionServiceImpl implements YoutubeCaptionService {

    private static final Logger logger = LoggerFactory.getLogger(YoutubeCaptionServiceImpl.class);
    private final String proxyUser = System.getenv("PROXY_USER");
    private final String proxyPass = System.getenv("PROXY_PASS");
    private final String proxyHost = defaultIfBlank(System.getenv("PROXY_HOST"), "p.webshare.io");
    private final String proxyPort = defaultIfBlank(System.getenv("PROXY_PORT"), "80");

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
            String proxyHost = trimToNull(System.getenv("PROXY_HOST"));
            String proxyPort = trimToNull(System.getenv("PROXY_PORT"));
            String proxyUser = trimToNull(System.getenv("PROXY_USER"));
            String proxyPass = trimToNull(System.getenv("PROXY_PASS"));

            // If env vars not provided, fallback to the webshare values configured earlier
            if (proxyHost == null) {
                proxyHost = proxyHost;
            }
            if (proxyPort == null) {
                proxyPort = proxyPort;
            }
            if (proxyUser == null) {
                proxyUser = proxyUser;
            }
            if (proxyPass == null) {
                proxyPass = proxyPass;
            }

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
logger.info("Proxy URL: {}", proxyUrl);

        YtDlpExecutionResult result;

        if (proxyUrl != null) {
            logger.info("Using proxy for yt-dlp request");
            result = executeYtDlp(buildYtDlpCommand(ytDlpCmd, youtubeUrl, proxyUrl));
            if (result.exitCode() != 0) {
                if (logger.isWarnEnabled()) {
                    logger.warn("yt-dlp failed via proxy (exit code {}). Retrying without proxy once. Output: {}",
                            result.exitCode(), summarizeYtDlpOutput(result.stderr()));
                }
                result = executeYtDlp(buildYtDlpCommand(ytDlpCmd, youtubeUrl, null));
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

        String videoTitle = root.path("title").asText("Unknown title");
        String channelName = root.path("channel").asText("Unknown channel");
        long durationSeconds = root.path("duration").asLong(0);
        String videoLength = formatDuration(durationSeconds);

        logger.info("Fetched YouTube metadata - title: '{}', channel: '{}', length: {}",
                videoTitle, channelName, videoLength);

        JsonNode subtitles = root.path("subtitles").path("en");
        String subtitleUrl = null;
        if (subtitles.isArray() && !subtitles.isEmpty()) {
            subtitleUrl = subtitles.get(0).path("url").asText();
        }

        String cleanedCaptions = null;

        if (subtitleUrl == null || subtitleUrl.isEmpty()) {
            String videoId = extractYoutubeVideoId(youtubeUrl);
            if (videoId != null && !videoId.isEmpty()) {
                YoutubeTranscriptApi youtubeTranscriptApi = TranscriptApiFactory.createDefault();
                TranscriptContent transcriptContent = youtubeTranscriptApi.getTranscript(videoId, "en");
                cleanedCaptions = buildCaptionsFromTranscript(transcriptContent);
                logger.info("Fetched transcript fallback using video id: {}", videoId);
            } else {
                logger.warn("Could not extract video id from URL: {}", youtubeUrl);
            }
        } else {
            logger.info("Captions found, downloading from: {}", subtitleUrl);

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

    private String buildCaptionsFromTranscript(TranscriptContent transcriptContent) {
        if (transcriptContent == null || transcriptContent.getContent() == null || transcriptContent.getContent().isEmpty()) {
            return null;
        }

        StringBuilder transcript = new StringBuilder();
        for (TranscriptContent.Fragment fragment : transcriptContent.getContent()) {
            if (fragment != null && fragment.getText() != null && !fragment.getText().isBlank()) {
                transcript.append(fragment.getText().trim()).append("\n");
            }
        }

        String text = transcript.toString().trim();
        return text.isEmpty() ? null : text;
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

    private String extractYoutubeVideoId(String youtubeUrl) {
        if (youtubeUrl == null || youtubeUrl.trim().isEmpty()) {
            return null;
        }

        try {
            URI uri = URI.create(youtubeUrl.trim());
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
            String path = uri.getPath() == null ? "" : uri.getPath();

            if (host.contains("youtu.be")) {
                return sanitizeVideoId(path.startsWith("/") ? path.substring(1) : path);
            }

            if (path.startsWith("/watch")) {
                String query = uri.getRawQuery();
                if (query != null) {
                    for (String param : query.split("&")) {
                        String[] parts = param.split("=", 2);
                        if (parts.length == 2 && "v".equals(parts[0])) {
                            String value = URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
                            return sanitizeVideoId(value);
                        }
                    }
                }
            }

            if (path.startsWith("/shorts/") || path.startsWith("/embed/") || path.startsWith("/v/")) {
                String[] segments = path.split("/");
                for (int i = 0; i < segments.length - 1; i++) {
                    if ("shorts".equals(segments[i]) || "embed".equals(segments[i]) || "v".equals(segments[i])) {
                        return sanitizeVideoId(segments[i + 1]);
                    }
                }
            }
        } catch (Exception ex) {
            logger.warn("Failed to parse YouTube URL for video id: {}", youtubeUrl, ex);
        }

        return null;
    }

    private String sanitizeVideoId(String candidate) {
        if (candidate == null) {
            return null;
        }

        String id = candidate.trim();
        int queryIndex = id.indexOf('?');
        if (queryIndex >= 0) {
            id = id.substring(0, queryIndex);
        }

        int fragmentIndex = id.indexOf('#');
        if (fragmentIndex >= 0) {
            id = id.substring(0, fragmentIndex);
        }

        return id.isEmpty() ? null : id;
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
        if (proxyUrl != null) {
            command.add("--proxy");
            command.add(proxyUrl);
        }
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
}
