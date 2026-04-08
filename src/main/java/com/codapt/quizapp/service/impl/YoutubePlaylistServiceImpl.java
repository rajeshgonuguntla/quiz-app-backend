package com.codapt.quizapp.service.impl;

import com.codapt.quizapp.service.YoutubePlaylistService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
// ...existing code...
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

@Service
public class YoutubePlaylistServiceImpl implements YoutubePlaylistService {

    private static final Logger logger = LoggerFactory.getLogger(YoutubePlaylistServiceImpl.class);

    @Override
    public List<String> getVideoUrls(String playlistUrl) throws Exception {
        logger.info("Extracting video URLs from playlist: {}", playlistUrl);

        // Use system-installed yt-dlp (or command specified by YTDLP_CMD)
        String ytDlpCmd = System.getenv("YTDLP_CMD");
        if (ytDlpCmd == null || ytDlpCmd.trim().isEmpty()) {
            ytDlpCmd = "yt-dlp";
        }

        String proxy = resolveProxyFromEnv();

        List<String> cmd = new ArrayList<>();
        cmd.add(ytDlpCmd);
        if (proxy != null) {
            cmd.add("--proxy");
            cmd.add(proxy);
        }
        cmd.add("--flat-playlist");
        cmd.add("--dump-json");
        cmd.add(playlistUrl);

        ProcessBuilder pb = new ProcessBuilder(cmd);

        Process process = pb.start();

        List<String> videoUrls = new ArrayList<>();
        ObjectMapper mapper = new ObjectMapper();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                try {
                    JsonNode entry = mapper.readTree(line);
                    String videoId = entry.path("id").asText(null);
                    if (videoId != null && !videoId.isBlank()) {
                        videoUrls.add("https://www.youtube.com/watch?v=" + videoId);
                    }
                } catch (Exception e) {
                    logger.warn("Failed to parse playlist entry, skipping: {}", line, e);
                }
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            logger.error("yt-dlp failed for playlist with exit code: {}", exitCode);
            throw new RuntimeException("Failed to retrieve playlist information from yt-dlp");
        }

        logger.info("Found {} videos in playlist: {}", videoUrls.size(), playlistUrl);
        return videoUrls;
    }

    private String resolveProxyFromEnv() {
        String p = System.getenv("YTDLP_PROXY_URL");
        if (p != null && !p.isBlank()) return p;
        p = System.getenv("HTTPS_PROXY");
        if (p != null && !p.isBlank()) return p;
        p = System.getenv("HTTP_PROXY");
        if (p != null && !p.isBlank()) return p;
        return null;
    }
}