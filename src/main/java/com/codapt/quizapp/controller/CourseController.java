package com.codapt.quizapp.controller;

import com.codapt.quizapp.dto.CourseGenerateRequest;
import com.codapt.quizapp.dto.CourseResponse;
import com.codapt.quizapp.service.CourseService;
import com.codapt.quizapp.util.YouTubeUrlValidator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/course")
@Tag(name = "Course Controller", description = "Handles course generation from YouTube videos")
public class CourseController {

    private static final Logger logger = LoggerFactory.getLogger(CourseController.class);

    private final CourseService courseService;

    public CourseController(CourseService courseService) {
        this.courseService = courseService;
    }

    @PostMapping("/generate")
    @Operation(summary = "Generate Course from YouTube Playlist",
            description = "Receives a YouTube playlist URL, extracts captions, and generates a structured course using Gemini AI")
    public CourseResponse generateCourse(@RequestBody CourseGenerateRequest request) {
        if (request == null || request.getYoutubeUrl() == null || request.getYoutubeUrl().trim().isEmpty()) {
            logger.warn("Invalid request: YouTube playlist URL is required");
            throw new IllegalArgumentException("YouTube playlist URL is required");
        }

        String url = request.getYoutubeUrl().trim();
        
        // Validate that the URL is a playlist and not a single video
        YouTubeUrlValidator.validatePlaylistUrl(url);
        
        logger.info("Received course generation request for YouTube playlist: {}", url);

        CourseResponse response = courseService.generateCourse(url);

        logger.info("Course generation completed for playlist: {} - title: '{}', modules: {}",
                url, response.getTitle(),
                response.getModules() != null ? response.getModules().size() : 0);

        return response;
    }
}