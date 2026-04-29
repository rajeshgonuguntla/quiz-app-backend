package com.codapt.quizapp.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Data Transfer Object for playlist video caption details.
 * Contains caption information for a single video in a playlist.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class PlaylistCaptionDetails {
    private String videoId;
    private String videoUrl;
    private String caption;
    private String videoTitle;
    private String channelName;
    private String videoLength;
    private Integer playlistIndex;
}
