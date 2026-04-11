package com.codapt.quizapp.service.impl;

import com.codapt.quizapp.service.YoutubePlaylistService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
// ...existing code...
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Service
public class YoutubePlaylistServiceImpl implements YoutubePlaylistService {

    private static final Logger logger = LoggerFactory.getLogger(YoutubePlaylistServiceImpl.class);
    private final String proxyUser = System.getenv("PROXY_USER");
    private final String proxyPass = System.getenv("PROXY_PASS");
    private final String proxyHost = defaultIfBlank(System.getenv("PROXY_HOST"), "p.webshare.io");
    private final String proxyPort = defaultIfBlank(System.getenv("PROXY_PORT"), "80");


    @Override
    public List<String> getVideoUrls(String playlistUrl) throws Exception {
        logger.info("Extracting video URLs from playlist: {}", playlistUrl);

        logger.info("Extracting playlist using yt-dlp: {}", playlistUrl);

        // Determine yt-dlp command
        String ytDlpCmd = trimToNull(System.getenv("YTDLP_CMD"));
        if (ytDlpCmd == null) {
            ytDlpCmd = "yt-dlp";
        }

        // Resolve proxy configuration (more flexible than just HTTPS_PROXY/HTTP_PROXY)
        String proxyUrl = null;
        String explicitProxy = trimToNull(System.getenv("PROXY_URL"));
        if (explicitProxy != null) {
            proxyUrl = explicitProxy;
        } else {
            String proxyHost = trimToNull(System.getenv("PROXY_HOST"));
            String proxyPort = trimToNull(System.getenv("PROXY_PORT"));
            String proxyUser = trimToNull(System.getenv("PROXY_USER"));
            String proxyPass = trimToNull(System.getenv("PROXY_PASS"));


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
                        sb.append(proxyUser).append(":").append(proxyPass).append("@");
                    }
                }
                sb.append(proxyHost).append(":").append(proxyPort);
                proxyUrl = sb.toString();
            }
        }

        YtDlpExecutionResult result;
        if (proxyUrl != null) {
            logger.info("Using proxy for yt-dlp request");
            result = executeYtDlp(buildYtDlpCommand(ytDlpCmd, playlistUrl, proxyUrl, true));
            if (result.exitCode() != 0) {
                if (logger.isWarnEnabled()) {
                    logger.warn("yt-dlp failed via proxy (exit code {}). Retrying without proxy once. Output: {}",
                            result.exitCode(), summarizeYtDlpOutput(result.stderr()));
                }
                result = executeYtDlp(buildYtDlpCommand(ytDlpCmd, playlistUrl, null, true));
            }
        } else {
            logger.warn("No proxy configured for yt-dlp. Direct request will be attempted.");
            result = executeYtDlp(buildYtDlpCommand(ytDlpCmd, playlistUrl, null, true));
        }

        if (result.exitCode() != 0) {
            if (logger.isErrorEnabled()) {
                logger.error("yt-dlp failed with exit code {}. Output: {}", result.exitCode(), summarizeYtDlpOutput(result.stderr()));
            }
            throw new RuntimeException("Failed to retrieve playlist information from yt-dlp (exit=" + result.exitCode() + ")");
        }

        List<String> videoUrls = new ArrayList<>();
        ObjectMapper mapper = new ObjectMapper();

        // yt-dlp with --flat-playlist and --dump-json returns one JSON object per line
        String stdout = result.stdout();
        if (stdout != null && !stdout.isBlank()) {
            try (BufferedReader reader = new BufferedReader(new StringReader(stdout))) {
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
        }

        logger.info("Found {} videos in playlist: {}", videoUrls.size(), playlistUrl);
        return videoUrls;
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

    private List<String> buildYtDlpCommand(String ytDlpCmd, String targetUrl, String proxyUrl, boolean flatPlaylist) {
        List<String> command = new ArrayList<>();
        command.add(ytDlpCmd);
        if (proxyUrl != null) {
            command.add("--proxy");
            command.add(proxyUrl);
        }
        if (flatPlaylist) {
            command.add("--flat-playlist");
        }
        command.add("--write-automatic-subs");
        command.add("--skip-download");
        command.add("--dump-json");
        command.add(targetUrl);
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

    // Legacy proxy resolver retained for compatibility with other code paths if ever needed.
    // Preferred proxy resolution is handled above using PROXY_URL or PROXY_{HOST,PORT,USER,PASS}.
}