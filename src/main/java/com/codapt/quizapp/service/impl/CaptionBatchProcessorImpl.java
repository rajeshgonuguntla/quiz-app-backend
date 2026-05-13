package com.codapt.quizapp.service.impl;

import com.codapt.quizapp.dto.PlaylistCaptionDetails;
import com.codapt.quizapp.service.CaptionBatchProcessorService;
import com.codapt.quizapp.service.GeminiService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;

/**
 * Implementation of CaptionBatchProcessorService for handling large playlists.
 * 
 * Features:
 * - Intelligently chunks captions to stay within token limits
 * - Processes chunks in batches with optional parallel processing
 * - Summarizes each batch and combines summaries for final course generation
 * - Configurable batch size and token limits
 */
@ConditionalOnExpression("'${spring.ai.google.genai.api-key}' != ''")
@Service
public class CaptionBatchProcessorImpl implements CaptionBatchProcessorService {

    private static final Logger logger = LoggerFactory.getLogger(CaptionBatchProcessorImpl.class);
    private static final int TOKENS_PER_WORD_ESTIMATE = 1;
    private static final int TOKENS_RESERVED_FOR_RESPONSE = 5000;

    private final GeminiService geminiService;
    private final int maxTokensPerBatch;
    private final int enableParallelProcessing;

    public CaptionBatchProcessorImpl(GeminiService geminiService,
                                    @Value("${playlist.batch.max-tokens:100000}") int maxTokensPerBatch,
                                    @Value("${playlist.batch.parallel-enabled:1}") int enableParallelProcessing) {
        this.geminiService = geminiService;
        this.maxTokensPerBatch = maxTokensPerBatch;
        this.enableParallelProcessing = enableParallelProcessing;
        logger.info("CaptionBatchProcessorImpl initialized with maxTokensPerBatch: {}, parallelProcessing: {}",
                maxTokensPerBatch, enableParallelProcessing == 1);
    }

    @Override
    public Map<String, Object> processCaptionsInBatches(List<PlaylistCaptionDetails> playlistCaptions) {
        logger.info("Starting batch processing for {} videos", playlistCaptions.size());

        List<List<PlaylistCaptionDetails>> batches = createBatches(playlistCaptions);
        logger.info("Created {} batches from {} videos", batches.size(), playlistCaptions.size());

        List<String> batchSummaries = processBatchesForSummary(batches);

        Map<String, Object> result = new HashMap<>();
        result.put("totalVideos", playlistCaptions.size());
        result.put("totalBatches", batches.size());
        result.put("batchSummaries", batchSummaries);
        result.put("batches", batches);

        logger.info("Batch processing completed: {} summaries generated", batchSummaries.size());
        return result;
    }

    @Override
    public String combineBatchSummaries(List<String> batchSummaries) {
        if (batchSummaries == null || batchSummaries.isEmpty()) {
            logger.warn("No batch summaries to combine");
            return "";
        }

        logger.info("Combining {} batch summaries into final course content", batchSummaries.size());

        if (batchSummaries.size() == 1) {
            logger.info("Single batch summary, returning as is");
            return batchSummaries.getFirst();
        }

        StringBuilder combinedPrompt = new StringBuilder();
        combinedPrompt.append("You are a course curriculum designer. You have received ").append(batchSummaries.size())
                .append(" summaries from different sections of a YouTube playlist course.\n\n");
        combinedPrompt.append("Your task is to combine these summaries into a coherent, well-structured course.\n");
        combinedPrompt.append("Ensure there's no duplication and create smooth transitions between topics.\n\n");
        combinedPrompt.append("SECTION SUMMARIES:\n");
        combinedPrompt.append("================\n\n");

        for (int i = 0; i < batchSummaries.size(); i++) {
            combinedPrompt.append("SECTION ").append(i + 1).append(":\n");
            combinedPrompt.append(batchSummaries.get(i)).append("\n\n");
        }

        combinedPrompt.append("Now combine these into a single comprehensive course structure with modules, topics, and learning objectives.");

        logger.debug("Sending combined summaries to Gemini for final synthesis");

        try {
            String combinedContent = geminiService.getCourseFromGemini(combinedPrompt.toString());
            logger.info("Successfully combined batch summaries into final course content");
            return combinedContent;
        } catch (Exception e) {
            logger.error("Failed to combine batch summaries", e);
            throw new RuntimeException("Failed to combine batch summaries: " + e.getMessage(), e);
        }
    }

    @Override
    public int estimateTokenCount(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int wordCount = text.split("\\s+").length;
        return wordCount * TOKENS_PER_WORD_ESTIMATE;
    }

    private List<List<PlaylistCaptionDetails>> createBatches(List<PlaylistCaptionDetails> allCaptions) {
        List<List<PlaylistCaptionDetails>> batches = new ArrayList<>();
        List<PlaylistCaptionDetails> currentBatch = new ArrayList<>();
        int currentBatchTokens = 0;
        int maxTokensAllowed = maxTokensPerBatch - TOKENS_RESERVED_FOR_RESPONSE;

        for (PlaylistCaptionDetails caption : allCaptions) {
            int videoTokens = estimateTokenCount(caption.getCaption());

            if (currentBatchTokens + videoTokens > maxTokensAllowed && !currentBatch.isEmpty()) {
                logger.debug("Batch complete with {} videos and ~{} tokens", currentBatch.size(), currentBatchTokens);
                batches.add(new ArrayList<>(currentBatch));
                currentBatch.clear();
                currentBatchTokens = 0;
            }

            currentBatch.add(caption);
            currentBatchTokens += videoTokens;
        }

        if (!currentBatch.isEmpty()) {
            logger.debug("Final batch: {} videos and ~{} tokens", currentBatch.size(), currentBatchTokens);
            batches.add(currentBatch);
        }

        return batches;
    }

    private List<String> processBatchesForSummary(List<List<PlaylistCaptionDetails>> batches) {
        if (enableParallelProcessing == 1 && batches.size() > 1) {
            return processBatchesParallel(batches);
        } else {
            return processBatchesSequential(batches);
        }
    }

    private List<String> processBatchesSequential(List<List<PlaylistCaptionDetails>> batches) {
        List<String> summaries = new ArrayList<>();

        for (int i = 0; i < batches.size(); i++) {
            try {
                String summary = processSingleBatch(batches.get(i), i + 1, batches.size());
                summaries.add(summary);
                logger.info("Processed batch {}/{}: generated summary", i + 1, batches.size());
            } catch (Exception e) {
                logger.error("Error processing batch {}: {}", i + 1, e.getMessage(), e);
                throw new RuntimeException("Failed to process batch " + (i + 1) + ": " + e.getMessage(), e);
            }
        }

        return summaries;
    }

    private List<String> processBatchesParallel(List<List<PlaylistCaptionDetails>> batches) {
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(4, batches.size()));
        List<CompletableFuture<String>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < batches.size(); i++) {
                final int batchIndex = i;
                final int batchNumber = i + 1;
                final int totalBatches = batches.size();

                CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
                    try {
                        logger.info("Processing batch {}/{} in parallel thread", batchNumber, totalBatches);
                        return processSingleBatch(batches.get(batchIndex), batchNumber, totalBatches);
                    } catch (Exception e) {
                        logger.error("Error in parallel batch processing {}/{}: {}", batchNumber, totalBatches, e.getMessage(), e);
                        throw new RuntimeException("Failed to process batch " + batchNumber, e);
                    }
                }, executor);
                futures.add(future);
            }

            List<String> summaries = new ArrayList<>();
            try {
                for (CompletableFuture<String> future : futures) {
                    summaries.add(future.get(10, TimeUnit.MINUTES));
                }
            } catch (TimeoutException e) {
                logger.error("Batch processing timed out after 10 minutes", e);
                Thread.currentThread().interrupt();
                throw new RuntimeException("Batch processing timed out", e);
            } catch (InterruptedException e) {
                logger.error("Batch processing interrupted: {}", e.getMessage(), e);
                Thread.currentThread().interrupt();
                throw new RuntimeException("Batch processing interrupted: " + e.getMessage(), e);
            } catch (ExecutionException e) {
                logger.error("Batch processing failed: {}", e.getMessage(), e);
                throw new RuntimeException("Batch processing failed: " + e.getMessage(), e);
            }

            return summaries;
        } finally {
            executor.shutdownNow();
        }
    }

    private String processSingleBatch(List<PlaylistCaptionDetails> batch, int batchNumber, int totalBatches) {
        StringBuilder batchContent = new StringBuilder();
        batchContent.append("=== BATCH ").append(batchNumber).append(" OF ").append(totalBatches).append(" ===\n");
        batchContent.append("Videos in this batch: ").append(batch.size()).append("\n\n");

        for (PlaylistCaptionDetails video : batch) {
            batchContent.append("--- VIDEO: ").append(video.getVideoTitle()).append(" ---\n");
            if (video.getCaption() != null && !video.getCaption().isBlank()) {
                batchContent.append(video.getCaption()).append("\n");
            }
            batchContent.append("\n");
        }

        logger.debug("Batch {}: Prepared content with ~{} tokens for Gemini processing",
                batchNumber, estimateTokenCount(batchContent.toString()));

        return summarizeBatchContent(batchContent.toString(), batchNumber, totalBatches);
    }

    private String summarizeBatchContent(String batchContent, int batchNumber, int totalBatches) {
        String summaryPrompt = String.format(
                "You are analyzing batch %d of %d from a YouTube playlist course. " +
                        "Extract and summarize the key learning points, topics, and concepts from this batch. " +
                        "Organize them logically and prepare them for merging with other batches into a cohesive course.%n%n" +
                        "BATCH CONTENT:%n%s",
                batchNumber, totalBatches, batchContent
        );

        try {
            logger.debug("Sending batch {} for Gemini summarization", batchNumber);
            String summary = geminiService.getCourseFromGemini(summaryPrompt);
            logger.info("Successfully summarized batch {}/{}", batchNumber, totalBatches);
            return summary;
        } catch (Exception e) {
            logger.error("Failed to summarize batch {}/{}: {}", batchNumber, totalBatches, e.getMessage(), e);
            throw new RuntimeException("Failed to summarize batch " + batchNumber + ": " + e.getMessage(), e);
        }
    }
}

