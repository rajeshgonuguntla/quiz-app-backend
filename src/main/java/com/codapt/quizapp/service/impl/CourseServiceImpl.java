package com.codapt.quizapp.service.impl;

import com.codapt.quizapp.dto.CourseResponse;
import com.codapt.quizapp.dto.PlaylistCaptionDetails;
import com.codapt.quizapp.service.CourseService;
import com.codapt.quizapp.service.CaptionBatchProcessorService;
import com.codapt.quizapp.service.GeminiService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@ConditionalOnExpression("'${spring.ai.google.genai.api-key}' != ''")
@Service
public class CourseServiceImpl implements CourseService {

    private static final Logger logger = LoggerFactory.getLogger(CourseServiceImpl.class);
    
    // Threshold for using batch processing (for playlists with many videos)
    private static final int BATCH_PROCESSING_THRESHOLD = 20;

    private final PlaylistCaptionServiceImpl playlistCaptionService;
    private final GeminiService geminiService;
    private final CaptionBatchProcessorService captionBatchProcessor;
    private final ObjectMapper objectMapper;
    private final int largeBatchThreshold;

    public CourseServiceImpl(PlaylistCaptionServiceImpl playlistCaptionService,
                             GeminiService geminiService,
                             CaptionBatchProcessorService captionBatchProcessor,
                             ObjectMapper objectMapper,
                             @Value("${playlist.large-batch-threshold:20}") int largeBatchThreshold) {
        this.playlistCaptionService = playlistCaptionService;
        this.geminiService = geminiService;
        this.captionBatchProcessor = captionBatchProcessor;
        this.objectMapper = objectMapper;
        this.largeBatchThreshold = largeBatchThreshold;
        logger.info("CourseServiceImpl initialized with largeBatchThreshold: {}", largeBatchThreshold);
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

        logger.info("Successfully downloaded captions for {} videos", playlistCaptions.size());
        String channelName = playlistCaptions.get(0).getChannelName();

        // Choose processing strategy based on playlist size
        String rawJson;
        if (playlistCaptions.size() > largeBatchThreshold) {
            logger.info("Large playlist detected ({} videos). Using batch processing.", playlistCaptions.size());
            rawJson = generateCourseUsingBatchProcessing(playlistCaptions);
        } else {
            logger.info("Small/medium playlist detected ({} videos). Using direct processing.", playlistCaptions.size());
            rawJson = generateCourseDirectly(playlistCaptions);
        }

        final CourseResponse courseResponse;
        try {
            String cleanJson = stripCodeFences(rawJson);
            logger.info("Clean course JSON generated, length: {}", cleanJson.length());
            courseResponse = objectMapper.readValue(cleanJson, CourseResponse.class);
        } catch (Exception e) {
            logger.error("Failed to parse course JSON from Gemini response. Raw length: {}", 
                    rawJson != null ? rawJson.length() : 0, e);
            throw new RuntimeException("Failed to parse generated course structure: " + e.getMessage(), e);
        }

        // Get first video details for metadata
        PlaylistCaptionDetails firstVideo = playlistCaptions.get(0);
        courseResponse.setVideoTitle(firstVideo.getVideoTitle());
        courseResponse.setChannelName(channelName != null ? channelName : firstVideo.getChannelName());
        courseResponse.setVideoLength(firstVideo.getVideoLength());
        courseResponse.setDate(LocalDate.now().format(DateTimeFormatter.ofPattern("M/d/yyyy")));

        logger.info("Course generation completed for playlist - title: '{}', modules: {}, videos processed: {}",
                courseResponse.getTitle(),
                courseResponse.getModules() != null ? courseResponse.getModules().size() : 0,
                playlistCaptions.size());

        return courseResponse;
    }

    /**
     * Generate course using batch processing for large playlists.
     * Divides videos into chunks, summarizes each chunk, then combines summaries.
     */
    private String generateCourseUsingBatchProcessing(List<PlaylistCaptionDetails> playlistCaptions) {
        logger.info("Starting batch-based course generation for {} videos", playlistCaptions.size());
        
        try {
            // Step 1: Process captions in batches
            Map<String, Object> batchResults = captionBatchProcessor.processCaptionsInBatches(playlistCaptions);
            @SuppressWarnings("unchecked")
            List<String> batchSummaries = (List<String>) batchResults.get("batchSummaries");
            int totalBatches = (int) batchResults.get("totalBatches");
            
            logger.info("Generated {} batch summaries. Now combining them into final course.", totalBatches);

            // Step 2: Combine batch summaries into final course structure
            String combinedContent = captionBatchProcessor.combineBatchSummaries(batchSummaries);
            logger.info("Successfully combined all batch summaries into final course content");
            
            return combinedContent;
        } catch (Exception e) {
            logger.error("Batch processing failed, falling back to direct processing: {}", e.getMessage());
            // Fallback: try direct processing if batch processing fails
            logger.warn("Attempting fallback: direct processing with all captions (may exceed limits)");
            return generateCourseDirectly(playlistCaptions);
        }
    }

    /**
     * Generate course directly by combining all captions and sending to Gemini.
     * Used for smaller playlists or as a fallback.
     */
    private String generateCourseDirectly(List<PlaylistCaptionDetails> playlistCaptions) {
        logger.info("Generating course directly from {} videos", playlistCaptions.size());
        
        // Combine captions from all videos in the playlist
        StringBuilder combinedCaptions = new StringBuilder();
        for (PlaylistCaptionDetails video : playlistCaptions) {
            String caption = video.getCaption();
            logger.debug("Processing video: '{}' - caption length: {}",
                    video.getVideoTitle(),
                    caption != null ? caption.length() : 0);
            if (caption != null && !caption.isBlank()) {
                combinedCaptions.append("--- Video: ").append(video.getVideoTitle()).append(" ---\n");
                combinedCaptions.append(caption).append("\n\n");
            }
        }

        logger.info("Combined captions size: {} characters (~{} tokens estimated)",
                combinedCaptions.length(),
                combinedCaptions.length() / 4); // Rough estimate

        try {
            logger.debug("Sending combined captions to Gemini");
            return geminiService.getCourseFromGemini(combinedCaptions.toString());
        } catch (Exception e) {
            logger.error("Failed to generate course from Gemini: {}", e.getMessage());
            throw new RuntimeException("Failed to generate course: " + e.getMessage(), e);
        }
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