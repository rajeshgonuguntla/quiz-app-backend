package com.codapt.quizapp.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class YoutubePlaylistQuizResponse {
    private String playlistUrl;
    private String caption;
    private String quiz;
    private String videoTitle;
    private String channelName;
    private String videoLength;
}