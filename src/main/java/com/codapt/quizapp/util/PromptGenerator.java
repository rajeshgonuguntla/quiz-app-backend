package com.codapt.quizapp.util;

public class PromptGenerator {

    public String promptTemplate= """
            You are an expert assessment designer and instructional evaluator.
            
            Your task is to generate a high-quality quiz based ONLY on the provided YouTube transcript content.
            
            You must follow all constraints strictly.
            
            ────────────────────────────
            TRANSCRIPT CONTENT:
            {{TRANSCRIPT_TEXT}}
            ────────────────────────────
            
            QUIZ REQUIREMENTS:
            - Generate {{QUESTION_COUNT}} multiple-choice questions.
            - Difficulty level: {{DIFFICULTY}} (easy / medium / hard).
            - Each question must test understanding, not surface memorization.
            - Avoid trivial facts unless clearly emphasized in the transcript.
            - Cover different key concepts from the content.
            - Do not invent information not present in the transcript.
            - Each question must have exactly 4 answer choices.
            - Only one answer is correct.
            - Provide a concise explanation (1–3 sentences) for the correct answer.
            
            QUESTION DESIGN RULES:
            - Mix conceptual, applied, and reasoning-based questions.
            - If the transcript includes examples, convert them into scenario-based questions.
            - If the content is technical, prioritize mechanism and cause-effect reasoning.
            - Do NOT repeat similar questions.
            - Do NOT reference timestamps or mention “the transcript”.
            
            OUTPUT FORMAT:
            Return ONLY valid JSON.
            Do NOT include markdown.
            Do NOT include commentary.
            Do NOT include explanations outside the JSON.
            
            Use this exact schema:
            
            {
              "title": "Quiz Title Based on Topic",
              "questions": [
                {
                  "id": "q1",
                  "question": "Question text here?",
                  "options": [
                    "Option A",
                    "Option B",
                    "Option C",
                    "Option D"
                  ],
                  "correctAnswerIndex": 0,
                  "explanation": "Brief explanation of why the answer is correct."
                }
              ]
            }
            
            Ensure:
            - JSON is syntactically valid.
            - correctAnswerIndex is 0–3.
            - Exactly {{QUESTION_COUNT}} questions are returned.
            """;
    public String coursePromptTemplate = """
            You are an expert instructional designer. Your task is to generate a structured online course outline based ONLY on the provided YouTube transcript content.

            ────────────────────────────
            TRANSCRIPT CONTENT:
            {{TRANSCRIPT_TEXT}}
            ────────────────────────────

            COURSE REQUIREMENTS:
            - Generate exactly {{MODULE_COUNT}} modules that logically cover the transcript content.
            - Each module must have 4–6 lessons with realistic durations.
            - Each module must have exactly 3 quiz questions testing that module's content.
            - The overall difficulty should reflect the transcript's complexity level.
            - Do NOT invent information not present in the transcript.

            LESSON DESIGN RULES:
            - Alternate between "video" and "reading" lesson types naturally.
            - Lesson titles should be specific and actionable.
            - Durations should be realistic (5–15 min for videos, 4–10 min for readings).
            - Lesson IDs are sequential across all modules: l1, l2, l3, ...
            - Module IDs are: m1, m2, m3, ...

            QUIZ DESIGN RULES:
            - Each quiz question must have exactly 4 options.
            - "answer" is the 0-based index (0–3) of the correct option.
            - Questions must test understanding, not surface recall.

            OUTPUT FORMAT:
            Return ONLY valid JSON. No markdown fences. No commentary. No text outside the JSON.

            Use this exact schema:

            {
              "title": "Course Title",
              "description": "2–3 sentence description of what this course covers and who it is for.",
              "difficulty": "Beginner",
              "modules": [
                {
                  "id": "m1",
                  "title": "Module Title",
                  "description": "1–2 sentence description of this module.",
                  "lessons": [
                    { "id": "l1", "title": "Lesson Title", "duration": "8 min", "type": "video" }
                  ],
                  "quiz": [
                    {
                      "question": "Question text?",
                      "options": ["Option A", "Option B", "Option C", "Option D"],
                      "answer": 1
                    }
                  ]
                }
              ]
            }

            Ensure:
            - JSON is syntactically valid.
            - difficulty is exactly one of: Beginner, Intermediate, Advanced.
            - lesson type is exactly "video" or "reading".
            - answer is 0–3.
            - Exactly {{MODULE_COUNT}} modules are returned.
            """;
}
