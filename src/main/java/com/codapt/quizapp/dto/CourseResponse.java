package com.codapt.quizapp.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class CourseResponse {
    private String title;
    private String description;
    private String difficulty;
    private String date;
    private List<ModuleDto> modules;
    private String videoTitle;
    private String channelName;
    private String videoLength;
}