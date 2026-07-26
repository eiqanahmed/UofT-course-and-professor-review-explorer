package com.uoft.reviewexplorer.model;

public record Professor(
        long id,
        String name,
        String department,
        String university,
        Double avgRating,
        Integer reviewCount,
        Double takeAgainPct,
        Double difficulty,
        String profileUrl
) {
}
