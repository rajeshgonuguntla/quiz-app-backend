package com.codapt.quizapp.service;

import com.codapt.quizapp.dto.PlaylistCaptionDetails;

import java.util.List;

/**
 * Service for downloading captions from YouTube playlists.
 * Handles batch caption extraction for multiple videos in a playlist.
 */
public interface PlaylistCaptionService {
    /**
     * Downloads captions for all videos in a YouTube playlist.
     * @param playlistUrl the URL of the YouTube playlist
     * @return a list of PlaylistCaptionDetails containing caption data for each video
     * @throws Exception if caption download or processing fails
     */
    List<PlaylistCaptionDetails> downloadPlaylistCaptions(String playlistUrl) throws Exception;
}

