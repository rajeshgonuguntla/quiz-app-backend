package com.codapt.quizapp.service.impl;

import com.codapt.quizapp.dto.PlaylistCaptionDetails;
import com.codapt.quizapp.service.PlaylistCaptionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Service for downloading captions from YouTube playlists using yt-dlp.
 * Uses auto-generated subtitles with English language preference and playlist indexing.
 */
@Service
public class PlaylistCaptionServiceImpl implements PlaylistCaptionService {

    private static final Logger logger = LoggerFactory.getLogger(PlaylistCaptionServiceImpl.class);

    /**
     * Downloads captions for all videos in a YouTube playlist.
     * Uses yt-dlp with: --write-auto-sub --sub-langs en --skip-download --dump-json
     */
    @Override
    public List<PlaylistCaptionDetails> downloadPlaylistCaptions(String playlistUrl) throws Exception {
        logger.info("Starting caption download for YouTube playlist: {}", playlistUrl);

        String ytDlpCmd = trimToNull(System.getenv("YTDLP_CMD"));
        if (ytDlpCmd == null) {
            ytDlpCmd = "yt-dlp";
        }

        String proxyUrl = buildProxyUrl();
        YtDlpExecutionResult result;

        logger.info("Proxy Url: {}", proxyUrl);
        if (proxyUrl != null) {
            logger.info("Using proxy for yt-dlp playlist caption download");
            result = executeYtDlp(buildPlaylistCaptionCommand(ytDlpCmd, playlistUrl, proxyUrl));
            if (result.exitCode() != 0) {
                logger.warn("yt-dlp failed via proxy. Retrying without proxy.");
                result = executeYtDlp(buildPlaylistCaptionCommand(ytDlpCmd, playlistUrl, null));
            }
        } else {
            logger.warn("No proxy configured. Direct request will be attempted.");
            result = executeYtDlp(buildPlaylistCaptionCommand(ytDlpCmd, playlistUrl, null));
        }

        logger.info("Result from YT-DLP command: {}", result);

        if (result.exitCode() != 0) {
            logger.error("yt-dlp failed with exit code {}", result.exitCode());
            throw new IllegalStateException("Failed to retrieve playlist from yt-dlp");
        }

        logger.info("Playlist captions downloaded successfully");
        return parsePlaylistJsonOutput(result.stdout());
    }

    private List<String> buildPlaylistCaptionCommand(String ytDlpCmd, String playlistUrl, String proxyUrl) {
        List<String> command = new ArrayList<>();
        command.add(ytDlpCmd);
        
        if (proxyUrl != null) {
            command.add("--proxy");
            command.add(proxyUrl);
        }
        
        command.add("-N");
        command.add("10");
        command.add("--playlist-items");
        command.add("1-");
        // Removed --flat-playlist to get full video data including subtitles/captions
        command.add("--write-auto-sub");
        command.add("--sub-langs");
        command.add("en");
        command.add("--skip-download");
        command.add("--dump-json");
        command.add(playlistUrl);
        
        return command;
    }

    private List<PlaylistCaptionDetails> parsePlaylistJsonOutput(String stdout) {
        List<PlaylistCaptionDetails> playlistCaptions = new ArrayList<>();
        ObjectMapper mapper = new ObjectMapper();

        if (stdout == null || stdout.isBlank()) {
            logger.warn("No output from yt-dlp");
            return playlistCaptions;
        }

        try (BufferedReader reader = new BufferedReader(new StringReader(stdout))) {
            String line;
            int playlistIndex = 0;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                try {
                    playlistIndex++;
                    JsonNode entry = mapper.readTree(line);
                    PlaylistCaptionDetails captionDetails = extractVideoDetails(entry, playlistIndex);
                    playlistCaptions.add(captionDetails);
                    logger.info("Processed video {}: {}", playlistIndex, 
                            captionDetails.getVideoTitle());
                } catch (Exception e) {
                    logger.warn("Failed to parse entry {}", playlistIndex, e);
                }
            }
        } catch (IOException e) {
            logger.error("Error reading yt-dlp output", e);
            throw new RuntimeException("Failed to parse playlist output", e);
        }

        logger.info("Processed {} videos from playlist", playlistCaptions.size());
        return playlistCaptions;
    }

    private PlaylistCaptionDetails extractVideoDetails(JsonNode entry, int playlistIndex) throws IOException {
        String videoId = entry.path("id").asText(null);
        String videoTitle = entry.path("title").asText("Unknown title");
        String channelName = entry.path("channel").asText("Unknown channel");
        long durationSeconds = entry.path("duration").asLong(0);
        String videoLength = formatDuration(durationSeconds);
        String videoUrl = "https://www.youtube.com/watch?v=" + videoId;
        
        String caption = extractAndCleanCaption(entry);

        PlaylistCaptionDetails details = new PlaylistCaptionDetails();
        details.setVideoId(videoId);
        details.setVideoUrl(videoUrl);
        details.setCaption(caption);
        details.setVideoTitle(videoTitle);
        details.setChannelName(channelName);
        details.setVideoLength(videoLength);
        details.setPlaylistIndex(playlistIndex);
        
        return details;
    }

    private String extractAndCleanCaption(JsonNode entry) {
        try {
            String videoId = entry.path("id").asText();
            
            // Try English subtitles first
            String captionUrl = extractCaptionUrl(entry.path("subtitles"), "en", videoId, "English subtitles");
            if (captionUrl != null) {
                return downloadAndCleanCaption(captionUrl);
            }

            // Try automatic captions for English
            captionUrl = extractCaptionUrl(entry.path("automatic_captions"), "en", videoId, "automatic captions");
            if (captionUrl != null) {
                return downloadAndCleanCaption(captionUrl);
            }

            // Try any available language from subtitles
            captionUrl = extractFirstAvailableCaptionUrl(entry.path("subtitles"), videoId, "alternative language subtitles");
            if (captionUrl != null) {
                return downloadAndCleanCaption(captionUrl);
            }

            // Try any available language from automatic captions
            captionUrl = extractFirstAvailableCaptionUrl(entry.path("automatic_captions"), videoId, "alternative language automatic captions");
            if (captionUrl != null) {
                return downloadAndCleanCaption(captionUrl);
            }

            logger.debug("No captions available for video {}", videoId);
            return "No captions available";
        } catch (Exception e) {
            logger.warn("Error extracting captions for video {}", entry.path("id").asText(), e);
            return "Error extracting captions";
        }
    }

    private String extractCaptionUrl(JsonNode captionNode, String language, String videoId, String description) {
        if (captionNode.isObject()) {
            JsonNode langCaptions = captionNode.path(language);
            if (langCaptions.isArray() && !langCaptions.isEmpty()) {
                String url = langCaptions.get(0).path("url").asText(null);
                if (url != null) {
                    logger.debug("Found {} for video {}", description, videoId);
                    return url;
                }
            }
        }
        return null;
    }

    private String extractFirstAvailableCaptionUrl(JsonNode captionNode, String videoId, String description) {
        if (captionNode.isObject()) {
            for (JsonNode langCaptions : captionNode) {
                if (langCaptions.isArray() && !langCaptions.isEmpty()) {
                    String url = langCaptions.get(0).path("url").asText(null);
                    if (url != null) {
                        logger.debug("Found {} for video {}", description, videoId);
                        return url;
                    }
                }
            }
        }
        return null;
    }

    private String downloadAndCleanCaption(String url) throws IOException {
        StringBuilder captions = new StringBuilder();
        try {
            URLConnection connection = URI.create(url).toURL().openConnection();
            connection.setConnectTimeout(10000); // 10 second timeout
            connection.setReadTimeout(10000);
            
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                int lineCount = 0;
                while ((line = reader.readLine()) != null && lineCount < 10000) { // Limit to prevent memory issues
                    captions.append(line).append("\n");
                    lineCount++;
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to download caption from URL: {}", url, e);
            throw new IOException("Failed to download captions", e);
        }
        return cleanCaptionText(captions.toString());
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

    private String buildProxyUrl() {
        String explicitProxy = trimToNull(System.getenv("PROXY_URL"));
        if (explicitProxy != null) {
            return explicitProxy;
        }

        String proxyHost = trimToNull(System.getenv("PROXY_HOST"));
        String proxyPort = trimToNull(System.getenv("PROXY_PORT"));
        String proxyUser = trimToNull(System.getenv("PROXY_USER"));
        String proxyPass = trimToNull(System.getenv("PROXY_PASS"));

        if (proxyHost != null && proxyPort != null) {
            StringBuilder sb = new StringBuilder();
            sb.append("http://");
            if (proxyUser != null && proxyPass != null) {
                sb.append(URLEncoder.encode(proxyUser, StandardCharsets.UTF_8))
                        .append(":")
                        .append(URLEncoder.encode(proxyPass, StandardCharsets.UTF_8))
                        .append("@");
            }
            sb.append(proxyHost).append(":").append(proxyPort);
            return sb.toString();
        }
        return null;
    }

    private YtDlpExecutionResult executeYtDlp(List<String> command) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        Process process = pb.start();

        CompletableFuture<String> stdoutFuture = CompletableFuture.supplyAsync(() -> readStream(process.getInputStream()));
        CompletableFuture<String> stderrFuture = CompletableFuture.supplyAsync(() -> readStream(process.getErrorStream()));

        boolean finished = process.waitFor(10, TimeUnit.MINUTES);
        if (!finished) {
            logger.warn("yt-dlp process timed out after 10 minutes, destroying process");
            process.destroyForcibly();
            throw new InterruptedException("yt-dlp process timed out");
        }
        int exitCode = process.exitValue();
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

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private record YtDlpExecutionResult(int exitCode, String stdout, String stderr) {
    }
}

