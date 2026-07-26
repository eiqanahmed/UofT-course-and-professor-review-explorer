package com.uoft.reviewexplorer.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uoft.reviewexplorer.model.Review;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

final class StudentExperiencePredictor {
    private static final double NEUTRAL_DIFFICULTY = 3.5;
    private static final double DIFFICULTY_WEIGHT = 0.5;
    private static final int OBSERVED_ONLY_REVIEWS = 15;
    private static final double SPARSE_SCORE_CEILING = 4.5;
    private static final Pattern TOKEN_PATTERN = Pattern.compile("(?U)\\b\\w\\w+\\b");

    private final Model profModel;
    private final Model courseModel;

    private StudentExperiencePredictor(Model profModel, Model courseModel) {
        this.profModel = profModel;
        this.courseModel = courseModel;
    }

    static StudentExperiencePredictor load() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        return new StudentExperiencePredictor(
                loadModel(mapper, "models/student_experience_prof.json"),
                loadModel(mapper, "models/student_experience_course.json")
        );
    }

    double observedScore(List<Review> reviews) {
        double avgQuality = average(reviews, Review::quality);
        double avgDifficulty = average(reviews, Review::difficulty);
        double difficultyPenalty = DIFFICULTY_WEIGHT * Math.max(0, avgDifficulty - NEUTRAL_DIFFICULTY);
        return clamp(avgQuality - difficultyPenalty, 0, 5);
    }

    double score(List<Review> group, List<Review> allReviews) {
        if (group.isEmpty()) {
            return Double.NaN;
        }
        double observed = observedScore(group);
        int count = group.size();
        if (count >= OBSERVED_ONLY_REVIEWS) {
            return observed;
        }

        OptionalDouble predicted = predictionFor(group, allReviews);
        if (predicted.isEmpty()) {
            return Math.min(observed, sparseScoreCeiling(count));
        }

        double evidenceWeight = count / (double) OBSERVED_ONLY_REVIEWS;
        double blended = (evidenceWeight * observed) + ((1.0 - evidenceWeight) * predicted.getAsDouble());
        return clamp(Math.min(blended, sparseScoreCeiling(count)), 0, 5);
    }

    OptionalDouble predictCourseProfessor(String courseCode, long professorId, List<Review> allReviews) {
        List<Review> courseReviews = allReviews.stream()
                .filter(review -> review.courseCode().equals(courseCode))
                .toList();
        if (courseReviews.isEmpty()) {
            return OptionalDouble.empty();
        }
        List<Review> professorReviews = allReviews.stream()
                .filter(review -> review.professorId() == professorId)
                .toList();
        return OptionalDouble.of(predictCourseProfessor(courseCode, professorId, professorReviews, courseReviews, allReviews));
    }

    private OptionalDouble predictionFor(List<Review> group, List<Review> allReviews) {
        Set<String> courses = group.stream().map(Review::courseCode).collect(Collectors.toSet());
        Set<Long> professorIds = group.stream().map(Review::professorId).collect(Collectors.toSet());

        if (courses.size() == 1 && professorIds.size() == 1) {
            String courseCode = courses.iterator().next();
            long professorId = professorIds.iterator().next();
            List<Review> professorReviews = allReviews.stream()
                    .filter(review -> review.professorId() == professorId)
                    .toList();
            List<Review> courseReviews = allReviews.stream()
                    .filter(review -> review.courseCode().equals(courseCode))
                    .toList();
            if (professorReviews.isEmpty() && courseReviews.isEmpty()) {
                return OptionalDouble.empty();
            }
            return OptionalDouble.of(predictCourseProfessor(courseCode, professorId, professorReviews, courseReviews, allReviews));
        }

        if (courses.size() == 1) {
            String courseCode = courses.iterator().next();
            if (group.isEmpty()) {
                return OptionalDouble.empty();
            }
            Map<String, Object> features = new HashMap<>();
            features.put("comment", group.stream().map(Review::comment).collect(Collectors.joining(" ")));
            features.put("course_code", courseCode);
            features.put("difficulty", averageWithFallback(group, Review::difficulty, average(allReviews, Review::difficulty)));
            features.put("thumbs_up", group.stream().mapToInt(Review::thumbsUp).average().orElse(0));
            features.put("thumbs_down", group.stream().mapToInt(Review::thumbsDown).average().orElse(0));
            features.put("grade", mode(group.stream().map(Review::grade).filter(grade -> !grade.equals("Unknown")).toList()).orElse("Unknown"));
            return OptionalDouble.of(courseModel.predict(features));
        }

        return OptionalDouble.empty();
    }

    private double predictCourseProfessor(String courseCode, long professorId, List<Review> professorReviews, List<Review> courseReviews, List<Review> allReviews) {
        List<Review> combined = dedupe(concat(professorReviews, courseReviews));
        Map<String, Object> features = new HashMap<>();
        features.put("comment", combined.stream().map(Review::comment).collect(Collectors.joining(" ")));
        features.put("course_code", courseCode);
        features.put("prof_id", professorId + ".0");
        features.put("difficulty", averageWithFallback(combined, Review::difficulty, average(allReviews, Review::difficulty)));
        features.put("thumbs_up", combined.stream().mapToInt(Review::thumbsUp).average().orElse(0));
        features.put("thumbs_down", combined.stream().mapToInt(Review::thumbsDown).average().orElse(0));
        features.put("grade", mode(combined.stream().map(Review::grade).filter(grade -> !grade.equals("Unknown")).toList()).orElse("Unknown"));
        features.put("prof_avg_quality", professorReviews.isEmpty() ? average(allReviews, Review::quality) : average(professorReviews, Review::quality));
        features.put("prof_avg_difficulty", professorReviews.isEmpty() ? average(allReviews, Review::difficulty) : average(professorReviews, Review::difficulty));
        features.put("prof_review_count", professorReviews.size());
        return profModel.predict(features);
    }

    private static Model loadModel(ObjectMapper mapper, String path) throws IOException {
        try (InputStream input = new ClassPathResource(path).getInputStream()) {
            ModelSpec spec = mapper.readValue(input, ModelSpec.class);
            return new Model(spec);
        }
    }

    private static List<Review> concat(List<Review> left, List<Review> right) {
        List<Review> combined = new ArrayList<>(left);
        combined.addAll(right);
        return combined;
    }

    private static List<Review> dedupe(List<Review> reviews) {
        Map<Long, Review> byId = new LinkedHashMap<>();
        reviews.forEach(review -> byId.putIfAbsent(review.id(), review));
        return new ArrayList<>(byId.values());
    }

    private static double average(List<Review> reviews, java.util.function.Function<Review, Double> getter) {
        return reviews.stream().map(getter).filter(value -> value != null).mapToDouble(Double::doubleValue).average().orElse(0);
    }

    private static double averageWithFallback(List<Review> reviews, java.util.function.Function<Review, Double> getter, double fallback) {
        return reviews.stream().map(getter).filter(value -> value != null).mapToDouble(Double::doubleValue).average().orElse(fallback);
    }

    private static <T> java.util.Optional<T> mode(List<T> values) {
        return values.stream()
                .collect(Collectors.groupingBy(java.util.function.Function.identity(), Collectors.counting()))
                .entrySet()
                .stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double sparseScoreCeiling(int reviewCount) {
        if (reviewCount >= OBSERVED_ONLY_REVIEWS) {
            return 5.0;
        }
        return SPARSE_SCORE_CEILING + (reviewCount / (double) OBSERVED_ONLY_REVIEWS) * (5.0 - SPARSE_SCORE_CEILING);
    }

    private static final class Model {
        private final Map<String, Integer> vocabulary;
        private final double[] idf;
        private final Set<String> stopWords;
        private final double[] textWeights;
        private final List<CategoricalBlock> categorical;
        private final NumericBlock numeric;
        private final double bias;

        private Model(ModelSpec spec) {
            this.vocabulary = spec.vocabulary;
            this.idf = toArray(spec.idf);
            this.stopWords = new HashSet<>(spec.stopWords);
            this.textWeights = toArray(spec.textWeights);
            this.categorical = spec.categorical.stream().map(CategoricalBlock::new).toList();
            this.numeric = new NumericBlock(spec.numeric);
            this.bias = spec.bias;
        }

        private double predict(Map<String, Object> features) {
            double score = bias + textScore(String.valueOf(features.getOrDefault("comment", "")));
            for (CategoricalBlock block : categorical) {
                score += block.score(features.get(block.feature));
            }
            score += numeric.score(features);
            return clamp(score, 0, 5);
        }

        private double textScore(String text) {
            Map<Integer, Integer> counts = new HashMap<>();
            Matcher matcher = TOKEN_PATTERN.matcher(text.toLowerCase(Locale.ROOT));
            while (matcher.find()) {
                String token = matcher.group();
                if (stopWords.contains(token)) {
                    continue;
                }
                Integer index = vocabulary.get(token);
                if (index != null) {
                    counts.merge(index, 1, Integer::sum);
                }
            }
            double norm = 0;
            Map<Integer, Double> tfidf = new HashMap<>();
            for (Map.Entry<Integer, Integer> entry : counts.entrySet()) {
                double value = entry.getValue() * idf[entry.getKey()];
                tfidf.put(entry.getKey(), value);
                norm += value * value;
            }
            if (norm == 0) {
                return 0;
            }
            double divisor = Math.sqrt(norm);
            return tfidf.entrySet().stream()
                    .mapToDouble(entry -> (entry.getValue() / divisor) * textWeights[entry.getKey()])
                    .sum();
        }
    }

    private static final class CategoricalBlock {
        private final String feature;
        private final Map<String, Double> weightsByCategory = new HashMap<>();

        private CategoricalBlock(CategoricalSpec spec) {
            this.feature = spec.feature;
            for (int index = 0; index < spec.categories.size(); index++) {
                weightsByCategory.put(spec.categories.get(index), spec.weights.get(index));
            }
        }

        private double score(Object rawValue) {
            if (rawValue == null) {
                return 0;
            }
            return weightsByCategory.getOrDefault(String.valueOf(rawValue), 0.0);
        }
    }

    private static final class NumericBlock {
        private final List<String> features;
        private final double[] mean;
        private final double[] scale;
        private final double[] weights;

        private NumericBlock(NumericSpec spec) {
            this.features = spec.features;
            this.mean = toArray(spec.mean);
            this.scale = toArray(spec.scale);
            this.weights = toArray(spec.weights);
        }

        private double score(Map<String, Object> values) {
            double score = 0;
            for (int index = 0; index < features.size(); index++) {
                Object raw = values.get(features.get(index));
                if (!(raw instanceof Number number)) {
                    continue;
                }
                double scaled = (number.doubleValue() - mean[index]) / scale[index];
                score += scaled * weights[index];
            }
            return score;
        }
    }

    private static double[] toArray(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).toArray();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class ModelSpec {
        public Map<String, Integer> vocabulary = Map.of();
        public List<Double> idf = List.of();
        public List<String> stopWords = List.of();
        public List<Double> textWeights = List.of();
        public List<CategoricalSpec> categorical = List.of();
        public NumericSpec numeric = new NumericSpec();
        public double bias;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class CategoricalSpec {
        public String feature = "";
        public List<String> categories = List.of();
        public List<Double> weights = List.of();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class NumericSpec {
        public List<String> features = List.of();
        public List<Double> mean = List.of();
        public List<Double> scale = List.of();
        public List<Double> weights = List.of();
    }
}
