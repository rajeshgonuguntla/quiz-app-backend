package com.codapt.quizapp.service;

import com.codapt.quizapp.dto.PlaylistCaptionDetails;
import java.util.List;
import java.util.Map;

/**
 * Service for processing captions from large playlists in batches.
 * Handles chunking, token management, and batch summarization.
 */
public interface CaptionBatchProcessorService {

    /**
     * Process large playlist captions by dividing them into manageable chunks.
     * Each chunk is sent to Gemini separately to avoid token limits.
     *
     * @param playlistCaptions List of caption details from all videos
     * @return Map containing processed chunks and their summaries
     */
    Map<String, Object> processCaptionsInBatches(List<PlaylistCaptionDetails> playlistCaptions);

    /**
     * Combine batch summaries into a coherent course structure.
     *
     * @param batchSummaries List of summaries from each batch
     * @return Combined course content
     */
    String combineBatchSummaries(List<String> batchSummaries);

    /**
     * Get estimated token count for a given text (approximate).
     *
     * @param text Text to estimate
     * @return Estimated token count
     */
    int estimateTokenCount(String text);
}

