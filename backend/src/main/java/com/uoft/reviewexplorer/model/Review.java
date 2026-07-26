package com.uoft.reviewexplorer.model;

import java.time.LocalDate;

public record Review(
        long id,
        long professorId,
        String professorName,
        String department,
        String courseCode,
        String rawCourseCode,
        String dateText,
        LocalDate date,
        Double quality,
        Double difficulty,
        String forCredit,
        String attendance,
        String wouldTakeAgain,
        String grade,
        String textbook,
        String comment,
        int thumbsUp,
        int thumbsDown
) {
}
