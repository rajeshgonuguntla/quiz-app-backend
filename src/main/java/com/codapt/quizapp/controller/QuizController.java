package com.codapt.quizapp.controller;

import com.codapt.quizapp.dto.YoutubeCaptionDetails;
import com.codapt.quizapp.dto.YoutubePlaylistQuizRequest;
import com.codapt.quizapp.dto.YoutubePlaylistQuizResponse;
import com.codapt.quizapp.dto.YoutubeQuizRequest;
import com.codapt.quizapp.dto.YoutubeQuizResponse;
import com.codapt.quizapp.service.GeminiService;
import com.codapt.quizapp.service.YoutubeCaptionService;
import com.codapt.quizapp.service.YoutubePlaylistService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.StringJoiner;

@RestController
@RequestMapping("/api/quiz")
@Tag(name = "Quiz Controller", description = "Handles quiz generation from YouTube videos")
public class QuizController {

    private static final Logger logger = LoggerFactory.getLogger(QuizController.class);

    private final YoutubeCaptionService youtubeCaptionService;
    private final GeminiService geminiService;
    private final YoutubePlaylistService youtubePlaylistService;

    public QuizController(YoutubeCaptionService youtubeCaptionService, GeminiService geminiService,
                          YoutubePlaylistService youtubePlaylistService) {
        this.youtubeCaptionService = youtubeCaptionService;
        this.geminiService = geminiService;
        this.youtubePlaylistService = youtubePlaylistService;
    }

    @PostMapping("/generate")
    @Operation(summary = "Generate Quiz from YouTube URL",
            description = "Receives a YouTube URL, extracts captions, and generates a quiz using Gemini AI")
    public YoutubeQuizResponse generateQuiz(@RequestBody YoutubeQuizRequest request) {
        if (request == null || request.getYoutubeUrl() == null || request.getYoutubeUrl().trim().isEmpty()) {
            logger.warn("Invalid request: YouTube URL is required");
            throw new IllegalArgumentException("YouTube URL is required");
        }

        String url = request.getYoutubeUrl().trim();
        logger.info("Received quiz generation request for YouTube URL: {}", url);

        final YoutubeCaptionDetails captionDetails;
        try {
            captionDetails = youtubeCaptionService.downloadCaptions(url);
        } catch (Exception e) {
            logger.error("Failed to download captions and metadata for URL: {}", url, e);
            throw new RuntimeException("Failed to download captions from YouTube: " + e.getMessage(), e);
        }

        logger.info("Extracted YouTube metadata for URL {} - title: '{}', channel: '{}', length: {}",
                url,
                captionDetails.getVideoTitle(),
                captionDetails.getChannelName(),
                captionDetails.getVideoLength());

        String quiz = geminiService.getQuizFromGemini(captionDetails.getCaption());
        logger.debug("Successfully generated quiz for URL: {}", url);

        YoutubeQuizResponse response = new YoutubeQuizResponse(
                captionDetails.getCaption(),
                quiz,
                captionDetails.getVideoTitle(),
                captionDetails.getChannelName(),
                captionDetails.getVideoLength());

        logger.info("Quiz generation completed successfully for URL: {}", url);
        logger.info("Response - videoTitle: '{}', channelName: '{}', videoLength: '{}', captionLength: {} chars, quizLength: {} chars",
                response.getVideoTitle(),
                response.getChannelName(),
                response.getVideoLength(),
                response.getCaption() != null ? response.getCaption().length() : 0,
                response.getQuiz() != null ? response.getQuiz().length() : 0);
        logger.debug("Full response: {}", response);

        return response;
    }

    @PostMapping("/generate-playlist")
    @Operation(summary = "Generate Quizzes from YouTube Playlist",
            description = "Receives a YouTube playlist URL and generates one quiz per video using Gemini AI")
    public YoutubePlaylistQuizResponse generatePlaylistQuiz(@RequestBody YoutubePlaylistQuizRequest request) {
        if (request == null || request.getPlaylistUrl() == null || request.getPlaylistUrl().trim().isEmpty()) {
            logger.warn("Invalid request: Playlist URL is required");
            throw new IllegalArgumentException("Playlist URL is required");
        }

        String playlistUrl = request.getPlaylistUrl().trim();
        logger.info("Received playlist quiz generation request for: {}", playlistUrl);

        List<String> videoUrls;
        try {
            videoUrls = youtubePlaylistService.getVideoUrls(playlistUrl);
        } catch (Exception e) {
            logger.error("Failed to retrieve video URLs from playlist: {}", playlistUrl, e);
            throw new RuntimeException("Failed to retrieve playlist videos: " + e.getMessage(), e);
        }

        if (videoUrls.isEmpty()) {
            logger.warn("No videos found in playlist: {}", playlistUrl);
            throw new RuntimeException("No videos found in the provided playlist URL");
        }

        StringJoiner combinedCaptions = new StringJoiner("\n\n");
        StringJoiner combinedTitles = new StringJoiner(", ");
        StringJoiner combinedLengths = new StringJoiner(", ");
        String channelName = "";
        int successCount = 0;

        for (String videoUrl : videoUrls) {
            try {
                logger.info("Processing video: {}", videoUrl);
                YoutubeCaptionDetails captionDetails = youtubeCaptionService.downloadCaptions(videoUrl);
                combinedCaptions.add(captionDetails.getCaption());
                combinedTitles.add(captionDetails.getVideoTitle());
                combinedLengths.add(captionDetails.getVideoLength());
                if (channelName.isEmpty() && captionDetails.getChannelName() != null) {
                    channelName = captionDetails.getChannelName();
                }
                successCount++;
                logger.info("Captions downloaded for video: {}", videoUrl);
            } catch (Exception e) {
                logger.error("Failed to download captions for video {}, skipping: {}", videoUrl, e.getMessage());
            }
        }

        if (successCount == 0) {
            throw new RuntimeException("Failed to download captions for any video in the playlist");
        }

        String allCaptions = combinedCaptions.toString();
        logger.info("Generating combined quiz from {}/{} videos for playlist: {}", successCount, videoUrls.size(), playlistUrl);
        String quiz = geminiService.getQuizFromGemini(allCaptions);

        YoutubePlaylistQuizResponse response = new YoutubePlaylistQuizResponse(
                playlistUrl,
                allCaptions,
                quiz,
                combinedTitles.toString(),
                channelName,
                combinedLengths.toString());

        logger.info("Playlist processing complete. Processed {}/{} videos for playlist: {}",
                successCount, videoUrls.size(), playlistUrl);
        logger.info("Response - videoTitle: '{}', channelName: '{}', videoLength: '{}', captionLength: {} chars, quizLength: {} chars",
                response.getVideoTitle(),
                response.getChannelName(),
                response.getVideoLength(),
                response.getCaption() != null ? response.getCaption().length() : 0,
                response.getQuiz() != null ? response.getQuiz().length() : 0);
        logger.debug("Full response: {}", response);

        return response;
    }
}
