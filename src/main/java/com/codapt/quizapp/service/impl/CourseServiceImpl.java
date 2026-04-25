package com.codapt.quizapp.service.impl;

import com.codapt.quizapp.dto.CourseResponse;
import com.codapt.quizapp.dto.YoutubeCaptionDetails;
import com.codapt.quizapp.service.CourseService;
import com.codapt.quizapp.service.GeminiService;
import com.codapt.quizapp.service.YoutubeCaptionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

@ConditionalOnExpression("'${spring.ai.google.genai.api-key}' != ''")
@Service
public class CourseServiceImpl implements CourseService {

    private static final Logger logger = LoggerFactory.getLogger(CourseServiceImpl.class);

    private final YoutubeCaptionService youtubeCaptionService;
    private final GeminiService geminiService;
    private final ObjectMapper objectMapper;

    public CourseServiceImpl(YoutubeCaptionService youtubeCaptionService,
                             GeminiService geminiService,
                             ObjectMapper objectMapper) {
        this.youtubeCaptionService = youtubeCaptionService;
        this.geminiService = geminiService;
        this.objectMapper = objectMapper;
    }

    @Override
    public CourseResponse generateCourse(String youtubeUrl) {
        logger.info("Starting course generation for URL: {}", youtubeUrl);

        final YoutubeCaptionDetails captionDetails;
        try {
            captionDetails = youtubeCaptionService.downloadCaptions(youtubeUrl);
        } catch (Exception e) {
            logger.error("Failed to download captions for URL: {}", youtubeUrl, e);
            throw new RuntimeException("Failed to download captions from YouTube: " + e.getMessage(), e);
        }

        logger.info("Captions downloaded for URL: {} - title: '{}', channel: '{}'",
                youtubeUrl, captionDetails.getVideoTitle(), captionDetails.getChannelName());

        String rawJson;
        try {
            rawJson = geminiService.getCourseFromGemini(captionDetails.getCaption());
        } catch (Exception e) {
            logger.error("Failed to generate course from Gemini for URL: {}", youtubeUrl, e);
            throw new RuntimeException("Failed to generate course: " + e.getMessage(), e);
        }

        final CourseResponse courseResponse;
        try {
            String cleanJson = stripCodeFences(rawJson);
            courseResponse = objectMapper.readValue(cleanJson, CourseResponse.class);
        } catch (Exception e) {
            logger.error("Failed to parse course JSON from Gemini response. Raw: {}", rawJson, e);
            throw new RuntimeException("Failed to parse generated course structure: " + e.getMessage(), e);
        }

        courseResponse.setVideoTitle(captionDetails.getVideoTitle());
        courseResponse.setChannelName(captionDetails.getChannelName());
        courseResponse.setVideoLength(captionDetails.getVideoLength());
        courseResponse.setDate(LocalDate.now().format(DateTimeFormatter.ofPattern("M/d/yyyy")));

        logger.info("Course generation completed for URL: {} - title: '{}', modules: {}",
                youtubeUrl, courseResponse.getTitle(),
                courseResponse.getModules() != null ? courseResponse.getModules().size() : 0);

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