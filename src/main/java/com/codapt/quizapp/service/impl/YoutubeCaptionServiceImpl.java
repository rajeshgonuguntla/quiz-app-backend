package com.codapt.quizapp.service.impl;

import com.codapt.quizapp.dto.YoutubeCaptionDetails;
import com.codapt.quizapp.service.YoutubeCaptionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.thoroldvix.api.TranscriptContent;
import io.github.thoroldvix.api.TranscriptApiFactory;
import io.github.thoroldvix.api.YoutubeTranscriptApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;


@Service
public class YoutubeCaptionServiceImpl implements YoutubeCaptionService {

    private static final Logger logger = LoggerFactory.getLogger(YoutubeCaptionServiceImpl.class);

    @Override
    public YoutubeCaptionDetails downloadCaptions(String youtubeUrl) throws Exception {
        logger.info("Starting caption download for YouTube URL: {}", youtubeUrl);

        ClassPathResource resource = new ClassPathResource("yt-dlp.exe");
        File exeFile = File.createTempFile("yt-dlp", ".exe");

        try (InputStream in = resource.getInputStream();
             OutputStream out = new FileOutputStream(exeFile)) {
            in.transferTo(out);
        }

        exeFile.setExecutable(true);
        logger.debug("yt-dlp executable prepared at: {}", exeFile.getAbsolutePath());

        ProcessBuilder pb = new ProcessBuilder(
                exeFile.getAbsolutePath(),
                "--dump-json",
                youtubeUrl
        );

        Process process = pb.start();
        logger.debug("yt-dlp process started for URL: {}", youtubeUrl);

        StringBuilder json = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                json.append(line);
            }
        }

        int exitCode = process.waitFor();
        logger.debug("yt-dlp process completed with exit code: {}", exitCode);

        if (exitCode != 0) {
            logger.error("yt-dlp failed with exit code: {}", exitCode);
            throw new RuntimeException("Failed to retrieve video information from yt-dlp");
        }

        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(json.toString());

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