package com.uoft.reviewexplorer.service;

import com.uoft.reviewexplorer.model.Professor;
import com.uoft.reviewexplorer.model.Review;
import jakarta.annotation.PostConstruct;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class ReviewExplorerService {
    private static final Path REVIEWS_PATH = Path.of("data_scraping", "cleaned_reviews.csv");
    private static final Path PROFESSORS_PATH = Path.of("data_scraping", "professor_data.csv");
    private static final double MAX_BAYESIAN_PRIOR_WEIGHT = 10.0;
    private static final double MIN_BAYESIAN_PRIOR_WEIGHT = 1.0;
    private static final int MIN_RELEVANT_PRIOR_REVIEWS = 8;
    private static final Set<String> STOP_WORDS = Set.of(
            "a", "an", "and", "are", "as", "at", "be", "but", "by", "for", "from", "had", "has", "have",
            "he", "her", "his", "i", "in", "is", "it", "of", "on", "or", "she", "that", "the", "this",
            "to", "was", "were", "with", "you", "your", "very", "really", "prof", "professor", "course"
    );
    private static final Map<String, List<String>> ASPECTS = Map.of(
            "lectures", List.of("lecture", "lectures", "slides", "class", "engaging", "explain", "explains"),
            "grading", List.of("grade", "grading", "marks", "marked", "curve", "rubric", "feedback"),
            "workload", List.of("workload", "assignment", "assignments", "reading", "project", "essay"),
            "exams", List.of("exam", "exams", "midterm", "final", "quiz", "quizzes", "test"),
            "communication", List.of("email", "communication", "responsive", "office hours", "helpful", "available")
    );
    private static final Map<String, Double> SENTIMENT_LEXICON = Map.ofEntries(
            Map.entry("accommodating", 1.3), Map.entry("amazing", 2.0), Map.entry("approachable", 1.5),
            Map.entry("best", 2.1), Map.entry("caring", 1.6), Map.entry("clear", 1.7),
            Map.entry("easy", 1.0), Map.entry("engaging", 1.5), Map.entry("excellent", 2.0),
            Map.entry("fair", 1.4), Map.entry("flexible", 1.3), Map.entry("fun", 1.1),
            Map.entry("good", 1.0), Map.entry("great", 1.7), Map.entry("helpful", 1.7),
            Map.entry("interesting", 1.1), Map.entry("kind", 1.4), Map.entry("organized", 1.5),
            Map.entry("recommend", 1.7), Map.entry("responsive", 1.3), Map.entry("supportive", 1.5),
            Map.entry("sweet", 1.2), Map.entry("understandable", 1.3), Map.entry("well", 0.8),
            Map.entry("avoid", -2.0), Map.entry("awful", -2.0), Map.entry("bad", -1.3),
            Map.entry("boring", -1.2), Map.entry("brutal", -1.8), Map.entry("confusing", -1.7),
            Map.entry("disorganized", -1.7), Map.entry("dry", -0.9), Map.entry("harsh", -1.6),
            Map.entry("horrible", -2.0), Map.entry("monotone", -1.0), Map.entry("rude", -1.8),
            Map.entry("terrible", -2.1), Map.entry("tough", -1.0), Map.entry("unclear", -1.6),
            Map.entry("unfair", -1.8), Map.entry("unhelpful", -1.8), Map.entry("worst", -2.3)
    );
    private static final Set<String> NEGATIONS = Set.of(
            "no", "not", "never", "none", "hardly", "barely", "without", "isnt", "wasnt", "dont", "doesnt", "didnt", "cant", "couldnt"
    );
    private static final Set<String> INTENSIFIERS = Set.of(
            "very", "really", "extremely", "super", "incredibly", "highly", "so", "too"
    );
    private static final Set<String> DIMINISHERS = Set.of(
            "somewhat", "slightly", "kinda", "kind", "little", "bit", "pretty"
    );
    private static final Map<String, Double> SENTIMENT_PHRASES = Map.ofEntries(
            Map.entry("would take again", 1.8), Map.entry("highly recommend", 2.0), Map.entry("easy to understand", 1.7),
            Map.entry("well organized", 1.6), Map.entry("office hours", 0.5), Map.entry("cares about", 1.5),
            Map.entry("do not recommend", -2.0), Map.entry("would not recommend", -2.0), Map.entry("hard to follow", -1.8),
            Map.entry("hard to understand", -1.8), Map.entry("waste of time", -2.0), Map.entry("not helpful", -1.8),
            Map.entry("not clear", -1.6), Map.entry("too fast", -1.1), Map.entry("terrible at explaining", -2.2)
    );

    private final List<Review> reviews = new ArrayList<>();
    private final Map<Long, Professor> professors = new HashMap<>();
    private final Map<String, Double> inverseDocumentFrequency = new HashMap<>();
    private StudentExperiencePredictor studentExperiencePredictor;
    private double globalQualityPrior = 3.5;

    private record BayesianPrior(double quality, int reviewCount, String label) {
    }

    private record SearchCriteria(
            String rawQuery,
            String entityQuery,
            String coursePrefix,
            String departmentQuery,
            boolean wantsCourses,
            boolean wantsProfessors,
            boolean explicitEntityIntent,
            int resultLimit
    ) {
    }

    @PostConstruct
    public void load() throws IOException {
        loadProfessors();
        loadReviews();
        studentExperiencePredictor = StudentExperiencePredictor.load();
        globalQualityPrior = reviews.stream()
                .map(Review::quality)
                .filter(value -> value != null)
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(3.5);
        buildSearchIndex();
    }

    public Map<String, Object> overview() {
        List<Map<String, Object>> topCourses = reviews.stream()
                .collect(Collectors.groupingBy(Review::courseCode))
                .entrySet()
                .stream()
                .map(entry -> withCourseFields(entry.getKey(), entry.getValue()))
                .sorted(summaryComparator())
                .limit(12)
                .toList();

        List<Map<String, Object>> topProfessors = reviews.stream()
                .collect(Collectors.groupingBy(Review::professorId))
                .entrySet()
                .stream()
                        .map(entry -> withProfessorFields(entry.getKey(), entry.getValue(), true))
                .sorted(summaryComparator())
                .limit(12)
                .toList();

        List<String> grades = reviews.stream()
                .map(Review::grade)
                .filter(grade -> !grade.equals("Unknown"))
                .distinct()
                .sorted()
                .toList();

        return Map.of(
                "totals", Map.of(
                        "reviews", reviews.size(),
                        "courses", reviews.stream().map(Review::courseCode).distinct().count(),
                        "professors", professors.size()
                ),
                "topCourses", topCourses,
                "topProfessors", topProfessors,
                "grades", grades
        );
    }

    public Map<String, Object> search(String query) {
        return search(Map.of("q", query));
    }

    public Map<String, Object> search(Map<String, String> params) {
        String query = clean(params.get("q"));
        Double experienceMin = parseDouble(params.get("experience_min"));
        SearchCriteria criteria = searchCriteria(query);
        List<Review> filtered = applyFilters(params);

        List<Map<String, Object>> courses = filtered.stream()
                .collect(Collectors.groupingBy(Review::courseCode))
                .entrySet()
                .stream()
                .filter(entry -> criteria.wantsCourses())
                .filter(entry -> matchesCourseSearch(entry.getKey(), entry.getValue(), criteria))
                .map(entry -> withCourseFields(entry.getKey(), entry.getValue()))
                .filter(summary -> experienceMin == null || (Double) summary.get("experienceScore") >= experienceMin)
                .sorted(summaryComparator())
                .limit(criteria.resultLimit())
                .toList();

        List<Map<String, Object>> professorMatches = filtered.stream()
                .collect(Collectors.groupingBy(Review::professorId))
                .entrySet()
                .stream()
                .filter(entry -> criteria.wantsProfessors())
                .filter(entry -> matchesProfessorSearch(entry.getValue(), criteria))
                .map(entry -> withProfessorFields(entry.getKey(), entry.getValue(), true))
                .filter(summary -> experienceMin == null || (Double) summary.get("experienceScore") >= experienceMin)
                .sorted(summaryComparator())
                .limit(criteria.resultLimit())
                .toList();

        List<Map<String, Object>> reviewMatches = criteria.explicitEntityIntent()
                ? List.of()
                : reviews(searchReviewParams(params));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("courses", courses);
        response.put("professors", professorMatches);
        response.put("reviews", reviewMatches);
        unseenCombinationPrediction(params).ifPresent(prediction -> response.put("combinationPrediction", prediction));
        return response;
    }

    public Optional<Map<String, Object>> courseDetail(String courseCode) {
        String normalized = clean(courseCode).toUpperCase(Locale.ROOT);
        List<Review> courseReviews = reviews.stream()
                .filter(review -> review.courseCode().equals(normalized))
                .toList();
        if (courseReviews.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> detail = withCourseFields(normalized, courseReviews);
        detail.put("professors", courseReviews.stream()
                .collect(Collectors.groupingBy(Review::professorId))
                .entrySet()
                .stream()
                .map(entry -> withProfessorFields(entry.getKey(), entry.getValue(), false))
                .sorted(summaryComparator())
                .toList());
        enrichEvidence(detail, courseReviews);
        return Optional.of(detail);
    }

    public Optional<Map<String, Object>> professorDetail(long professorId) {
        List<Review> professorReviews = reviews.stream()
                .filter(review -> review.professorId() == professorId)
                .toList();
        if (professorReviews.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> detail = withProfessorFields(professorId, professorReviews, true);
        detail.put("courses", professorReviews.stream()
                .collect(Collectors.groupingBy(Review::courseCode))
                .entrySet()
                .stream()
                .map(entry -> withCourseFields(entry.getKey(), entry.getValue()))
                .sorted(summaryComparator())
                .toList());
        enrichEvidence(detail, professorReviews);
        return Optional.of(detail);
    }

    private Optional<Map<String, Object>> unseenCombinationPrediction(Map<String, String> params) {
        String course = clean(params.get("course")).toUpperCase(Locale.ROOT);
        String professorQuery = clean(params.get("professor"));
        if (course.isBlank() || professorQuery.isBlank()) {
            return Optional.empty();
        }

        Optional<Long> professorId = resolveProfessorId(professorQuery);
        if (professorId.isEmpty()) {
            return Optional.empty();
        }

        boolean exactCombinationHasReviews = reviews.stream()
                .anyMatch(review -> review.courseCode().equals(course) && review.professorId() == professorId.get());
        if (exactCombinationHasReviews) {
            return Optional.empty();
        }

        long courseReviewCount = reviews.stream().filter(review -> review.courseCode().equals(course)).count();
        if (courseReviewCount == 0) {
            return Optional.empty();
        }

        OptionalDouble prediction = studentExperiencePredictor.predictCourseProfessor(course, professorId.get(), reviews);
        if (prediction.isEmpty()) {
            return Optional.empty();
        }

        Professor professor = professors.get(professorId.get());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("courseCode", course);
        payload.put("professorId", professorId.get());
        payload.put("professorName", professor == null ? professorQuery : professor.name());
        payload.put("department", professor == null ? "Unknown Department" : professor.department());
        payload.put("experienceScore", round(prediction.getAsDouble(), 2));
        payload.put("courseReviewCount", courseReviewCount);
        payload.put("professorReviewCount", reviews.stream().filter(review -> review.professorId() == professorId.get()).count());
        payload.put("reason", "No direct reviews for this course/professor combination. Score is predicted from related course and professor review evidence.");
        return Optional.of(payload);
    }

    private Optional<Long> resolveProfessorId(String query) {
        Long id = parseLong(query);
        if (id != null && professors.containsKey(id)) {
            return Optional.of(id);
        }

        String normalized = formatName(query).toLowerCase(Locale.ROOT);
        return professors.entrySet()
                .stream()
                .filter(entry -> entry.getValue().name().toLowerCase(Locale.ROOT).equals(normalized))
                .map(Map.Entry::getKey)
                .findFirst()
                .or(() -> professors.entrySet()
                        .stream()
                        .filter(entry -> entry.getValue().name().toLowerCase(Locale.ROOT).contains(normalized))
                        .map(Map.Entry::getKey)
                        .findFirst());
    }

    public List<Map<String, Object>> reviews(Map<String, String> params) {
        List<Review> filtered = applyFilters(params);
        String query = clean(params.get("q"));
        int limit = parseInt(params.get("limit"), 50);
        if (query.isBlank()) {
            return filtered.stream()
                    .sorted(Comparator.comparing(Review::date, Comparator.nullsLast(Comparator.reverseOrder())))
                    .limit(limit)
                    .map(this::reviewPayload)
                    .toList();
        }
        Map<String, Double> queryVector = tfidfVector(query);
        Set<String> queryTerms = tokens(query).stream()
                .filter(token -> !STOP_WORDS.contains(token))
                .collect(Collectors.toSet());
        return filtered.stream()
                .map(review -> Map.entry(review, reviewSearchScore(queryVector, queryTerms, review.comment())))
                .filter(entry -> entry.getValue() > 0)
                .sorted(Map.Entry.<Review, Double>comparingByValue().reversed())
                .limit(limit)
                .map(entry -> {
                    Map<String, Object> payload = reviewPayload(entry.getKey());
                    payload.put("matchScore", round(entry.getValue(), 3));
                    return payload;
                })
                .toList();
    }

    private Map<String, String> searchReviewParams(Map<String, String> params) {
        Map<String, String> reviewParams = new HashMap<>(params);
        reviewParams.putIfAbsent("limit", "12");
        return reviewParams;
    }

    private void loadProfessors() throws IOException {
        try (Reader reader = Files.newBufferedReader(PROFESSORS_PATH);
             CSVParser parser = CSVFormat.DEFAULT.builder()
                     .setHeader()
                     .setSkipHeaderRecord(true)
                     .setAllowMissingColumnNames(true)
                     .build()
                     .parse(reader)) {
            for (CSVRecord row : parser) {
                Long id = parseLong(row.get("rmp_professor_id"));
                if (id == null) {
                    continue;
                }
                professors.put(id, new Professor(
                        id,
                        formatName(row.get("name")),
                        cleanOr(row.get("department"), "Unknown Department"),
                        cleanOr(row.get("university"), "University of Toronto"),
                        parseDouble(row.get("avg_rating")),
                        parseIntObject(row.get("num_reviews")),
                        parseDouble(row.get("take_again_pct")),
                        parseDouble(row.get("difficulty")),
                        clean(row.get("rmp_profile_url"))
                ));
            }
        }
    }

    private void loadReviews() throws IOException {
        try (Reader reader = Files.newBufferedReader(REVIEWS_PATH);
             CSVParser parser = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build().parse(reader)) {
            long id = 0;
            for (CSVRecord row : parser) {
                Long professorId = parseLong(row.get("prof_id"));
                if (professorId == null) {
                    continue;
                }
                Professor professor = professors.get(professorId);
                String courseCode = cleanOr(row.get("normalized_course_code"), row.get("course_code")).toUpperCase(Locale.ROOT);
                reviews.add(new Review(
                        id++,
                        professorId,
                        professor == null ? "Unknown Professor" : professor.name(),
                        professor == null ? "Unknown Department" : professor.department(),
                        courseCode,
                        clean(row.get("course_code")),
                        clean(row.get("date")),
                        parseDate(row.get("date")),
                        parseDouble(row.get("quality")),
                        parseDouble(row.get("difficulty")),
                        cleanOr(row.get("for_credit"), "Unknown"),
                        cleanOr(row.get("attendance"), "Unknown"),
                        cleanOr(row.get("would_take_again"), "Unknown"),
                        cleanOr(row.get("grade"), "Unknown"),
                        cleanOr(row.get("textbook"), "Unknown"),
                        clean(row.get("comment")),
                        parseInt(row.get("thumbs_up"), 0),
                        parseInt(row.get("thumbs_down"), 0)
                ));
            }
        }
    }

    private Map<String, Object> withCourseFields(String courseCode, List<Review> group) {
        Map<String, Object> payload = summarize(group);
        payload.put("courseCode", courseCode);
        payload.put("professorCount", group.stream().map(Review::professorId).distinct().count());
        return payload;
    }

    private Map<String, Object> withProfessorFields(long professorId, List<Review> group, boolean professorOnly) {
        Map<String, Object> payload = summarize(group);
        if (professorOnly) {
            payload.put("experienceScore", payload.get("bayesianQuality"));
        }
        payload.put("professorId", professorId);
        payload.put("professorName", group.get(0).professorName());
        payload.put("department", group.get(0).department());
        payload.put("courseCount", group.stream().map(Review::courseCode).distinct().count());
        Professor professor = professors.get(professorId);
        if (professor != null) {
            payload.put("profileUrl", professor.profileUrl());
        }
        return payload;
    }

    private Map<String, Object> summarize(List<Review> group) {
        double avgQuality = average(group, Review::quality);
        double avgDifficulty = average(group, Review::difficulty);
        double disagreement = standardDeviation(group.stream().map(Review::quality).toList());
        BayesianPrior prior = relevantQualityPrior(group);
        long takeAgainVotes = group.stream().filter(review -> !review.wouldTakeAgain().equals("Unknown")).count();
        long attendanceVotes = group.stream().filter(review -> !review.attendance().equals("Unknown")).count();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reviewCount", group.size());
        payload.put("avgQuality", round(avgQuality, 2));
        payload.put("avgDifficulty", round(avgDifficulty, 2));
        double priorWeight = bayesianPriorWeight(group.size());
        payload.put("bayesianQuality", bayesian(avgQuality, group.size(), prior.quality(), priorWeight));
        payload.put("bayesianPrior", round(prior.quality(), 2));
        payload.put("bayesianPriorLabel", prior.label());
        payload.put("bayesianPriorReviewCount", prior.reviewCount());
        payload.put("bayesianPriorWeight", round(priorWeight, 2));
        double experienceScore = studentExperiencePredictor.score(group, reviews);
        payload.put("experienceScore", round(experienceScore, 2));
        payload.put("takeAgainPct", takeAgainVotes == 0 ? null : round(percent(group, Review::wouldTakeAgain, "Yes"), 1));
        payload.put("attendanceRequiredPct", attendanceVotes == 0 ? null : round(percent(group, Review::attendance, "Mandatory"), 1));
        payload.put("mostCommonGrade", mode(group.stream().map(Review::grade).filter(grade -> !grade.equals("Unknown")).toList()).orElse("Unknown"));
        payload.put("disagreement", round(disagreement, 2));
        payload.put("confidence", confidence(group.size(), disagreement));
        return payload;
    }

    private void enrichEvidence(Map<String, Object> detail, List<Review> group) {
        detail.put("representativeReviews", representativeReviews(group));
        detail.put("aspects", aspectSentiment(group));
        detail.put("themes", themes(group));
    }

    private Map<String, Object> representativeReviews(List<Review> group) {
        List<Review> withText = group.stream().filter(review -> !review.comment().isBlank()).toList();
        List<Review> source = withText.isEmpty() ? group : withText;
        return Map.of(
                "positive", source.stream().max(Comparator.comparingDouble(review -> textSentiment(review.comment()))).map(this::reviewPayload).orElse(null),
                "neutral", source.stream().min(Comparator.comparingDouble(review -> Math.abs(textSentiment(review.comment())))).map(this::reviewPayload).orElse(null),
                "negative", source.stream().min(Comparator.comparingDouble(review -> textSentiment(review.comment()))).map(this::reviewPayload).orElse(null)
        );
    }

    private List<Map<String, Object>> aspectSentiment(List<Review> group) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<String, List<String>> aspect : ASPECTS.entrySet()) {
            List<Review> hits = group.stream()
                    .filter(review -> containsAny(review.comment().toLowerCase(Locale.ROOT), aspect.getValue()))
                    .toList();
            if (hits.isEmpty()) {
                continue;
            }
            double sentiment = hits.stream()
                    .mapToDouble(review -> textSentiment(review.comment()))
                    .average()
                    .orElse(0);
            String label = sentiment > 0.12 ? "Positive" : sentiment < -0.12 ? "Negative" : "Neutral";
            result.add(Map.of(
                    "aspect", aspect.getKey(),
                    "mentions", hits.size(),
                    "sentiment", round(sentiment, 2),
                    "label", label
            ));
        }
        return result;
    }

    private List<Map<String, Object>> themes(List<Review> group) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<String, List<String>> aspect : ASPECTS.entrySet()) {
            List<Review> hits = group.stream()
                    .filter(review -> containsAny(review.comment().toLowerCase(Locale.ROOT), aspect.getValue()))
                    .toList();
            if (hits.isEmpty()) {
                continue;
            }
            Map<String, Long> terms = hits.stream()
                    .map(Review::comment)
                    .map(this::tokens)
                    .flatMap(Collection::stream)
                    .filter(token -> !STOP_WORDS.contains(token))
                    .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
            List<String> keywords = terms.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .limit(4)
                    .map(Map.Entry::getKey)
                    .toList();
            result.add(Map.of(
                    "name", capitalize(aspect.getKey()),
                    "keywords", keywords,
                    "reviewCount", hits.size(),
                    "avgQuality", round(average(hits, Review::quality), 2),
                    "representativeReview", representativeForKeywords(hits, keywords).map(this::reviewPayload).orElse(reviewPayload(hits.get(0)))
            ));
        }
        return result.stream()
                .sorted(Comparator.comparing(item -> -((Integer) item.get("reviewCount"))))
                .toList();
    }

    private List<Review> applyFilters(Map<String, String> params) {
        String course = clean(params.get("course")).toUpperCase(Locale.ROOT);
        String professorId = clean(params.get("prof_id"));
        String professor = clean(params.get("professor")).toLowerCase(Locale.ROOT);
        String grade = clean(params.get("grade"));
        Double difficultyMin = parseDouble(params.get("difficulty_min"));
        Double difficultyMax = parseDouble(params.get("difficulty_max"));
        Double ratingMin = parseDouble(params.get("rating_min"));
        Double ratingMax = parseDouble(params.get("rating_max"));
        LocalDate dateStart = parseIsoDate(params.get("date_start"));
        LocalDate dateEnd = parseIsoDate(params.get("date_end"));

        return reviews.stream()
                .filter(review -> course.isBlank() || review.courseCode().equals(course))
                .filter(review -> professorId.isBlank() || String.valueOf(review.professorId()).equals(professorId))
                .filter(review -> professor.isBlank()
                        || String.valueOf(review.professorId()).equals(professor)
                        || review.professorName().toLowerCase(Locale.ROOT).contains(professor))
                .filter(review -> grade.isBlank() || review.grade().equals(grade))
                .filter(review -> difficultyMin == null || nullTo(review.difficulty(), 0.0) >= difficultyMin)
                .filter(review -> difficultyMax == null || nullTo(review.difficulty(), 5.0) <= difficultyMax)
                .filter(review -> ratingMin == null || nullTo(review.quality(), 0.0) >= ratingMin)
                .filter(review -> ratingMax == null || nullTo(review.quality(), 5.0) <= ratingMax)
                .filter(review -> dateStart == null || (review.date() != null && !review.date().isBefore(dateStart)))
                .filter(review -> dateEnd == null || (review.date() != null && !review.date().isAfter(dateEnd)))
                .toList();
    }

    private Map<String, Object> reviewPayload(Review review) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", review.id());
        payload.put("courseCode", review.courseCode());
        payload.put("professorId", review.professorId());
        payload.put("professorName", review.professorName());
        payload.put("date", review.dateText().isBlank() ? "Unknown" : review.dateText());
        payload.put("quality", review.quality());
        payload.put("difficulty", review.difficulty());
        payload.put("grade", review.grade());
        payload.put("attendance", review.attendance());
        payload.put("wouldTakeAgain", review.wouldTakeAgain());
        payload.put("comment", review.comment());
        payload.put("thumbsUp", review.thumbsUp());
        payload.put("thumbsDown", review.thumbsDown());
        return payload;
    }

    private void buildSearchIndex() {
        Map<String, Integer> documentCounts = new HashMap<>();
        for (Review review : reviews) {
            new HashSet<>(tokens(review.comment())).forEach(token -> documentCounts.merge(token, 1, Integer::sum));
        }
        int documentTotal = Math.max(1, reviews.size());
        for (Map.Entry<String, Integer> entry : documentCounts.entrySet()) {
            inverseDocumentFrequency.put(entry.getKey(), Math.log((1.0 + documentTotal) / (1.0 + entry.getValue())) + 1.0);
        }
    }

    private Map<String, Double> tfidfVector(String text) {
        Map<String, Long> counts = tokens(text).stream()
                .filter(token -> !STOP_WORDS.contains(token))
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        Map<String, Double> vector = new HashMap<>();
        for (Map.Entry<String, Long> entry : counts.entrySet()) {
            vector.put(entry.getKey(), entry.getValue() * inverseDocumentFrequency.getOrDefault(entry.getKey(), 1.0));
        }
        return vector;
    }

    private double reviewSearchScore(Map<String, Double> queryVector, Set<String> queryTerms, String comment) {
        Map<String, Double> commentVector = tfidfVector(comment);
        if (commentVector.isEmpty()) {
            return 0;
        }
        long matchedTerms = queryTerms.stream().filter(commentVector::containsKey).count();
        if (queryTerms.size() > 1 && matchedTerms == 0) {
            return 0;
        }
        if (queryTerms.size() <= 2 && matchedTerms < queryTerms.size()) {
            return 0;
        }
        if (queryTerms.size() > 2 && matchedTerms < Math.ceil(queryTerms.size() / 2.0)) {
            return 0;
        }
        double coverage = queryTerms.isEmpty() ? 1.0 : matchedTerms / (double) queryTerms.size();
        return cosine(queryVector, commentVector) * (0.75 + 0.25 * coverage);
    }

    private List<String> tokens(String text) {
        return Arrays.stream(clean(text).toLowerCase(Locale.ROOT).split("[^a-z0-9]+"))
                .filter(token -> token.length() > 2)
                .map(ReviewExplorerService::normalizeSearchToken)
                .toList();
    }

    private double cosine(Map<String, Double> left, Map<String, Double> right) {
        if (left.isEmpty() || right.isEmpty()) {
            return 0;
        }
        double dot = 0;
        for (Map.Entry<String, Double> entry : left.entrySet()) {
            dot += entry.getValue() * right.getOrDefault(entry.getKey(), 0.0);
        }
        double leftNorm = Math.sqrt(left.values().stream().mapToDouble(value -> value * value).sum());
        double rightNorm = Math.sqrt(right.values().stream().mapToDouble(value -> value * value).sum());
        return leftNorm == 0 || rightNorm == 0 ? 0 : dot / (leftNorm * rightNorm);
    }

    private Comparator<Map<String, Object>> summaryComparator() {
        return Comparator.<Map<String, Object>, Double>comparing(item -> (Double) item.get("experienceScore")).reversed()
                .thenComparing(item -> -((Integer) item.get("reviewCount")));
    }

    private String professorHaystack(List<Review> group) {
        return (group.get(0).professorName() + " " + group.get(0).department() + " "
                + group.stream().map(Review::courseCode).distinct().collect(Collectors.joining(" ")))
                .toLowerCase(Locale.ROOT);
    }

    private boolean matchesCourseSearch(String courseCode, List<Review> group, SearchCriteria criteria) {
        if (criteria.rawQuery().isBlank()) {
            return true;
        }
        String normalizedCourse = courseCode.toLowerCase(Locale.ROOT);
        if (!criteria.coursePrefix().isBlank()) {
            return normalizedCourse.startsWith(criteria.coursePrefix());
        }
        return normalizedCourse.contains(criteria.entityQuery())
                || group.stream().anyMatch(review -> review.professorName().toLowerCase(Locale.ROOT).contains(criteria.entityQuery()))
                || matchesReviewEvidence(group, criteria.entityQuery());
    }

    private boolean matchesProfessorSearch(List<Review> group, SearchCriteria criteria) {
        if (criteria.rawQuery().isBlank()) {
            return true;
        }
        if (!criteria.coursePrefix().isBlank()) {
            return group.stream().anyMatch(review -> review.courseCode().toLowerCase(Locale.ROOT).startsWith(criteria.coursePrefix()))
                    || group.get(0).department().toLowerCase(Locale.ROOT).contains(criteria.departmentQuery());
        }
        return professorHaystack(group).contains(criteria.entityQuery())
                || matchesReviewEvidence(group, criteria.entityQuery());
    }

    private boolean matchesReviewEvidence(List<Review> group, String query) {
        Set<String> queryTerms = tokens(query).stream()
                .filter(token -> !STOP_WORDS.contains(token))
                .collect(Collectors.toSet());
        if (queryTerms.isEmpty()) {
            return false;
        }
        return group.stream().anyMatch(review -> {
            Set<String> commentTerms = new HashSet<>(tokens(review.comment()));
            long matchedTerms = queryTerms.stream().filter(commentTerms::contains).count();
            if (queryTerms.size() <= 2) {
                return matchedTerms == queryTerms.size();
            }
            return matchedTerms >= Math.ceil(queryTerms.size() / 2.0);
        });
    }

    private Optional<Review> representativeForKeywords(List<Review> hits, List<String> keywords) {
        return hits.stream().max(Comparator.comparingInt(review ->
                keywords.stream().mapToInt(keyword -> review.comment().toLowerCase(Locale.ROOT).contains(keyword) ? 1 : 0).sum()));
    }

    private boolean containsAny(String text, List<String> words) {
        return words.stream().anyMatch(text::contains);
    }

    private double textSentiment(String text) {
        String normalized = clean(text).toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return 0;
        }

        double score = 0;
        int signals = 0;
        for (Map.Entry<String, Double> phrase : SENTIMENT_PHRASES.entrySet()) {
            if (normalized.contains(phrase.getKey())) {
                score += phrase.getValue();
                signals++;
            }
        }

        List<String> terms = tokens(normalized);
        for (int index = 0; index < terms.size(); index++) {
            Double value = SENTIMENT_LEXICON.get(terms.get(index));
            if (value == null) {
                continue;
            }
            double multiplier = 1.0;
            int lookbackStart = Math.max(0, index - 3);
            for (int previous = lookbackStart; previous < index; previous++) {
                String context = terms.get(previous);
                if (NEGATIONS.contains(context)) {
                    multiplier *= -1.0;
                } else if (INTENSIFIERS.contains(context)) {
                    multiplier *= 1.35;
                } else if (DIMINISHERS.contains(context)) {
                    multiplier *= 0.65;
                }
            }
            score += value * multiplier;
            signals++;
        }

        if (signals == 0) {
            return 0;
        }
        return clamp(score / Math.max(3.0, signals), -1, 1);
    }

    private Map<String, Object> confidence(int count, double disagreement) {
        double countScore = Math.min(1.0, count / 25.0);
        double agreementScore = Math.max(0.0, 1.0 - disagreement / 1.6);
        int score = (int) Math.round((0.72 * countScore + 0.28 * agreementScore) * 100);
        String label = score >= 75 ? "High" : score >= 45 ? "Medium" : "Low";
        return Map.of("label", label, "score", score, "reason", count + " reviews, " + round(disagreement, 2) + " rating disagreement");
    }

    private double average(List<Review> group, Function<Review, Double> getter) {
        return group.stream().map(getter).filter(value -> value != null).mapToDouble(Double::doubleValue).average().orElse(0);
    }

    private double percent(List<Review> group, Function<Review, String> getter, String match) {
        long usable = group.stream().map(getter).filter(value -> !value.equals("Unknown")).count();
        if (usable == 0) {
            return 0;
        }
        long yes = group.stream().map(getter).filter(match::equals).count();
        return yes * 100.0 / usable;
    }

    private double standardDeviation(List<Double> values) {
        List<Double> cleanValues = values.stream().filter(value -> value != null).toList();
        if (cleanValues.size() <= 1) {
            return 0;
        }
        double mean = cleanValues.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double variance = cleanValues.stream().mapToDouble(value -> Math.pow(value - mean, 2)).average().orElse(0);
        return Math.sqrt(variance);
    }

    private BayesianPrior relevantQualityPrior(List<Review> group) {
        Set<String> courseCodes = group.stream().map(Review::courseCode).collect(Collectors.toSet());
        Set<String> prefixes = courseCodes.stream()
                .map(ReviewExplorerService::coursePrefix)
                .filter(prefix -> !prefix.isBlank())
                .collect(Collectors.toSet());
        Set<Long> professorIds = group.stream().map(Review::professorId).collect(Collectors.toSet());

        if (!prefixes.isEmpty()) {
            List<Review> relevantReviews = reviews.stream()
                    .filter(review -> prefixes.contains(coursePrefix(review.courseCode())))
                    .filter(review -> !courseCodes.contains(review.courseCode()))
                    .filter(review -> professorIds.size() != 1 || !professorIds.contains(review.professorId()))
                    .filter(review -> review.quality() != null)
                    .toList();

            if (relevantReviews.size() >= MIN_RELEVANT_PRIOR_REVIEWS) {
                String label = prefixes.size() == 1
                        ? prefixes.iterator().next() + " subject average"
                        : "related subject average";
                return new BayesianPrior(average(relevantReviews, Review::quality), relevantReviews.size(), label);
            }
        }

        long globalCount = reviews.stream().filter(review -> review.quality() != null).count();
        return new BayesianPrior(globalQualityPrior, (int) globalCount, "global average");
    }

    private double bayesian(double avg, int count, double priorQuality, double priorWeight) {
        return round(((avg * count) + (priorQuality * priorWeight)) / (count + priorWeight), 2);
    }

    private double bayesianPriorWeight(int reviewCount) {
        if (reviewCount <= 0) {
            return MAX_BAYESIAN_PRIOR_WEIGHT;
        }
        double taperedWeight = MAX_BAYESIAN_PRIOR_WEIGHT * Math.sqrt(5.0 / Math.max(5.0, reviewCount));
        return clamp(taperedWeight, MIN_BAYESIAN_PRIOR_WEIGHT, MAX_BAYESIAN_PRIOR_WEIGHT);
    }

    private static String coursePrefix(String courseCode) {
        String cleaned = clean(courseCode).toUpperCase(Locale.ROOT);
        int index = 0;
        while (index < cleaned.length() && Character.isLetter(cleaned.charAt(index))) {
            index++;
        }
        return cleaned.substring(0, index);
    }

    private static SearchCriteria searchCriteria(String query) {
        String raw = clean(query).toLowerCase(Locale.ROOT);
        boolean asksForCourses = containsWord(raw, "course") || containsWord(raw, "courses") || containsWord(raw, "class") || containsWord(raw, "classes");
        boolean asksForProfessors = containsWord(raw, "prof") || containsWord(raw, "profs")
                || containsWord(raw, "professor") || containsWord(raw, "professors")
                || containsWord(raw, "instructor") || containsWord(raw, "instructors");

        String coursePrefix = "";
        String departmentQuery = "";
        String entityQuery = raw
                .replaceAll("\\b(courses?|classes?|profs?|professors?|instructors?)\\b", " ")
                .replaceAll("\\s+", " ")
                .trim();

        if (raw.contains("computer science") || containsWord(raw, "csc") || containsWord(raw, "cs")) {
            coursePrefix = "csc";
            departmentQuery = "computer";
            entityQuery = entityQuery
                    .replace("computer science", " ")
                    .replaceAll("\\b(csc|cs)\\b", " ")
                    .replaceAll("\\s+", " ")
                    .trim();
        } else if (containsWord(raw, "math") || containsWord(raw, "mat") || containsWord(raw, "mathematics")) {
            coursePrefix = "mat";
            departmentQuery = "mathematics";
            entityQuery = entityQuery
                    .replaceAll("\\b(math|mat|mathematics)\\b", " ")
                    .replaceAll("\\s+", " ")
                    .trim();
        } else if (raw.matches("[a-z]{2,4}")) {
            coursePrefix = raw;
            departmentQuery = raw;
        }

        boolean explicitEntityIntent = asksForCourses || asksForProfessors;
        boolean wantsCourses = !asksForProfessors || asksForCourses;
        boolean wantsProfessors = !asksForCourses || asksForProfessors;
        int resultLimit = !coursePrefix.isBlank() && explicitEntityIntent ? 1000 : 12;
        String fallbackEntityQuery = entityQuery.isBlank() ? raw : entityQuery;
        return new SearchCriteria(raw, fallbackEntityQuery, coursePrefix, departmentQuery, wantsCourses, wantsProfessors, explicitEntityIntent, resultLimit);
    }

    private static boolean containsWord(String text, String word) {
        return Pattern.compile("\\b" + Pattern.quote(word) + "\\b").matcher(text).find();
    }

    private static String normalizeSearchToken(String token) {
        return switch (token) {
            case "easiest", "easier" -> "easy";
            case "exams" -> "exam";
            case "quizzes" -> "quiz";
            case "assignments" -> "assignment";
            case "lectures" -> "lecture";
            case "readings" -> "reading";
            default -> token.endsWith("s") && token.length() > 4 ? token.substring(0, token.length() - 1) : token;
        };
    }

    private Optional<String> mode(List<String> values) {
        return values.stream()
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()))
                .entrySet()
                .stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey);
    }

    private static String formatName(String raw) {
        return clean(raw).replaceAll("(?<=[a-z])(?=[A-Z])", " ");
    }

    private static String clean(String value) {
        if (value == null || value.equalsIgnoreCase("nan")) {
            return "";
        }
        return Pattern.compile("\\s+").matcher(value.trim()).replaceAll(" ");
    }

    private static String cleanOr(String value, String fallback) {
        String cleaned = clean(value);
        return cleaned.isBlank() ? fallback : cleaned;
    }

    private static Double parseDouble(String value) {
        try {
            String cleaned = clean(value);
            return cleaned.isBlank() ? null : Double.parseDouble(cleaned);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Long parseLong(String value) {
        Double number = parseDouble(value);
        return number == null ? null : number.longValue();
    }

    private static int parseInt(String value, int fallback) {
        Integer number = parseIntObject(value);
        return number == null ? fallback : number;
    }

    private static Integer parseIntObject(String value) {
        Double number = parseDouble(value);
        return number == null ? null : number.intValue();
    }

    private static LocalDate parseIsoDate(String value) {
        String cleaned = clean(value);
        if (cleaned.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(cleaned);
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    private static LocalDate parseDate(String value) {
        String cleaned = clean(value)
                .replace("st,", ",")
                .replace("nd,", ",")
                .replace("rd,", ",")
                .replace("th,", ",");
        if (cleaned.isBlank()) {
            return null;
        }
        DateTimeFormatter formatter = new DateTimeFormatterBuilder()
                .parseCaseInsensitive()
                .appendPattern("MMM d, yyyy")
                .parseDefaulting(ChronoField.HOUR_OF_DAY, 0)
                .toFormatter(Locale.US);
        try {
            return LocalDate.parse(cleaned, formatter);
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    private static double nullTo(Double value, double fallback) {
        return value == null ? fallback : value;
    }

    private static double round(double value, int places) {
        double scale = Math.pow(10, places);
        return Math.round(value * scale) / scale;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String capitalize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.substring(0, 1).toUpperCase(Locale.ROOT) + value.substring(1);
    }
}
