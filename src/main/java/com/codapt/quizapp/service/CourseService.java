package com.codapt.quizapp.service;

import com.codapt.quizapp.dto.CourseResponse;

public interface CourseService {

    CourseResponse generateCourse(String youtubeUrl);
}