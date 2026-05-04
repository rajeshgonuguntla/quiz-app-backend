package com.codapt.quizapp.service.impl;

import com.codapt.quizapp.dto.CourseResponse;
import com.codapt.quizapp.dto.PlaylistCaptionDetails;
import com.codapt.quizapp.service.CourseService;
import com.codapt.quizapp.service.GeminiService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

@ConditionalOnExpression("'${spring.ai.google.genai.api-key}' != ''")
@Service
public class CourseServiceImpl implements CourseService {

    private static final Logger logger = LoggerFactory.getLogger(CourseServiceImpl.class);

    private final PlaylistCaptionServiceImpl playlistCaptionService;
    private final GeminiService geminiService;
    private final ObjectMapper objectMapper;

    public CourseServiceImpl(PlaylistCaptionServiceImpl playlistCaptionService,
                             GeminiService geminiService,
                             ObjectMapper objectMapper) {
        this.playlistCaptionService = playlistCaptionService;
        this.geminiService = geminiService;
        this.objectMapper = objectMapper;
    }

    @Override
    public CourseResponse generateCourse(String playlistUrl) {
        logger.info("Starting course generation for playlist: {}", playlistUrl);

        final List<PlaylistCaptionDetails> playlistCaptions;
        try {
            playlistCaptions = playlistCaptionService.downloadPlaylistCaptions(playlistUrl);
        } catch (Exception e) {
            logger.error("Failed to download captions for playlist: {}", playlistUrl, e);
            throw new RuntimeException("Failed to download captions from YouTube playlist: " + e.getMessage(), e);
        }

        if (playlistCaptions == null || playlistCaptions.isEmpty()) {
            logger.error("No videos found in playlist: {}", playlistUrl);
            throw new RuntimeException("Playlist is empty or could not be processed");
        }

        logger.info("Successfully downloaded captions : {}", playlistCaptions);
        logger.info("Downloaded captions for {} videos from playlist: {}", playlistCaptions.size(), playlistUrl);

        // Combine captions from all videos in the playlist
        StringBuilder combinedCaptions = new StringBuilder();
        String channelName = null;
        for (PlaylistCaptionDetails video : playlistCaptions) {
            String caption = video.getCaption();
            logger.info("Processing video: '{}' - caption length: {}, channel: '{}'",
                    video.getVideoTitle(),
                    caption != null ? caption.length() : 0,
                    video.getChannelName());
            logger.info("Caption: '{}' - caption length: {}, title: '{}'",
                    video.getCaption(),
                    caption != null ? caption.length() : 0,
                    video.getVideoTitle());
            if (caption != null && !caption.isBlank()) {
                combinedCaptions.append(caption).append("\n\n");
            }
            if (channelName == null) {
                channelName = video.getChannelName();
            }
        }

        String rawJson;
        try {
            logger.info("combined captions : {}", combinedCaptions.toString());
            rawJson = geminiService.getCourseFromGemini(combinedCaptions.toString());
        } catch (Exception e) {
            logger.error("Failed to generate course from Gemini for playlist: {}", playlistUrl, e);
            throw new RuntimeException("Failed to generate course: " + e.getMessage(), e);
        }

        final CourseResponse courseResponse;
        try {
            String cleanJson = stripCodeFences(rawJson);
            logger.info("Clean course JSON: {}", cleanJson);
            courseResponse = objectMapper.readValue(cleanJson, CourseResponse.class);
        } catch (Exception e) {
            logger.error("Failed to parse course JSON from Gemini response. Raw: {}", rawJson, e);
            throw new RuntimeException("Failed to parse generated course structure: " + e.getMessage(), e);
        }

        // Get first video details for metadata
        PlaylistCaptionDetails firstVideo = playlistCaptions.get(0);
        courseResponse.setVideoTitle(firstVideo.getVideoTitle());
        courseResponse.setChannelName(channelName != null ? channelName : firstVideo.getChannelName());
        courseResponse.setVideoLength(firstVideo.getVideoLength());
        courseResponse.setDate(LocalDate.now().format(DateTimeFormatter.ofPattern("M/d/yyyy")));

        logger.info("Course generation completed for playlist: {} - title: '{}', modules: {}, videos processed: {}",
                playlistUrl, courseResponse.getTitle(),
                courseResponse.getModules() != null ? courseResponse.getModules().size() : 0,
                playlistCaptions.size());

        return courseResponse;
    }

    private String stripCodeFences(String raw) {
        if (raw == null) return null;
        String trimmed = raw.strip();
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceFirst("```[a-zA-Z]*\\s*", "");
            int lastFence = trimmed.lastIndexOf("```");
            if (lastFence >= 0) {
                trimmed = trimmed.substring(0, lastFence);
            }
        }
        return trimmed.strip();
    }
}