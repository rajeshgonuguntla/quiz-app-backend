package com.codapt.quizapp.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class YoutubePlaylistQuizResponse {
    private String playlistUrl;
    private List<YoutubeQuizResponse> quizzes;
}