package com.codapt.quizapp.service;

import java.util.List;

public interface YoutubePlaylistService {
    List<String> getVideoUrls(String playlistUrl) throws Exception;
}