package com.codapt.quizapp.util;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.regex.Pattern;

/**
 * Utility class to validate YouTube URLs and ensure they are playlists.
 */
public class YouTubeUrlValidator {

    private static final String PLAYLIST_PATTERN = "[?&]list=[a-zA-Z0-9_-]+";

    /**
     * Validates that the provided URL is a valid YouTube playlist URL.
     * Throws an exception if the URL is a single video or invalid.
     *
     * @param youtubeUrl the YouTube URL to validate
     * @throws IllegalArgumentException if the URL is not a valid playlist URL
     */
    public static void validatePlaylistUrl(String youtubeUrl) {
        if (youtubeUrl == null || youtubeUrl.trim().isEmpty()) {
            throw new IllegalArgumentException("YouTube URL cannot be null or empty");
        }

        String urlTrimmed = youtubeUrl.trim();

        // Validate basic URL structure
        try {
            new URL(urlTrimmed);
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("Invalid URL format: " + urlTrimmed, e);
        }

        // Check if it's a YouTube URL
        if (!isYouTubeUrl(urlTrimmed)) {
            throw new IllegalArgumentException("URL must be a YouTube URL");
        }

        // Check if it contains a playlist parameter
        if (!isPlaylistUrl(urlTrimmed)) {
            throw new IllegalArgumentException("Only YouTube playlists are supported. Single videos are not allowed. Please provide a playlist URL with a 'list' parameter.");
        }
    }

    /**
     * Checks if the URL is a YouTube URL.
     *
     * @param url the URL to check
     * @return true if it's a YouTube URL, false otherwise
     */
    private static boolean isYouTubeUrl(String url) {
        return url.contains("youtube.com") || url.contains("youtu.be");
    }

    /**
     * Checks if the URL contains a playlist parameter.
     *
     * @param url the YouTube URL to check
     * @return true if it's a playlist URL, false otherwise
     */
    private static boolean isPlaylistUrl(String url) {
        return Pattern.compile(PLAYLIST_PATTERN).matcher(url).find();
    }
}

