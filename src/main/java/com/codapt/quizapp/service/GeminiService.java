package com.codapt.quizapp.service;

public interface GeminiService {

    public String getQuizFromGemini(String prompt);

    String getCourseFromGemini(String transcript);
}
