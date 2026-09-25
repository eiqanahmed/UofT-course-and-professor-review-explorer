package com.uoft.reviewexplorer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uoft.reviewexplorer.model.Review;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class GeminiSummaryService {
    private static final String DEFAULT_MODEL = "models/gemini-flash-latest";
    private static final Path DOTENV_PATH = Path.of(".env");
    private static final Pattern TOKEN_SPLIT = Pattern.compile("[^a-z0-9]+");
    private static final String ASPECT_QUERY = """
            course difficulty workload assignments homework projects essays exams midterms finals quizzes grading lectures teaching style clarity organization communication helpfulness
            """;
    private static final Set<String> STOP_WORDS = Set.of(
            "a", "about", "above", "after", "again", "against", "all", "am", "an", "and", "any", "are", "as", "at",
            "be", "because", "been", "before", "being", "below", "between", "both", "but", "by", "can", "did", "do",
            "does", "doing", "down", "during", "each", "few", "for", "from", "further", "had", "has", "have", "having",
            "he", "her", "here", "hers", "herself", "him", "himself", "his", "how", "i", "if", "in", "into", "is",
            "it", "its", "itself", "just", "me", "more", "most", "my", "myself", "no", "nor", "not", "now", "of",
            "off", "on", "once", "only", "or", "other", "our", "ours", "ourselves", "out", "over", "own", "same",
            "she", "should", "so", "some", "such", "than", "that", "the", "their", "theirs", "them", "themselves",
            "then", "there", "these", "they", "this", "those", "through", "to", "too", "under", "until", "up",
            "very", "was", "we", "were", "what", "when", "where", "which", "while", "who", "whom", "why", "with",
            "you", "your", "yours", "yourself", "yourselves"
    );

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, SummaryResult> summaryCache = new ConcurrentHashMap<>();

    public record SummaryResult(String summary, String confidence) {
    }

    public Optional<SummaryResult> summarizeUnseenCombination(
            String courseCode,
            long professorId,
            String professorName,
            List<Review> courseReviews,
            List<Review> professorReviews
    ) {
        String apiKey = configValue("GEMINI_API_KEY").orElse("");
        if (apiKey == null || apiKey.isBlank()) {
            return Optional.empty();
        }

        String cacheKey = "v2:" + courseCode.toUpperCase(Locale.ROOT) + ":" + professorId;
        SummaryResult cached = summaryCache.get(cacheKey);
        if (cached != null) {
            return Optional.of(cached);
        }

        String confidence = confidenceLabel(courseReviews.size(), professorReviews.size());
        try {
            String text = cleanSummary(callGemini(apiKey, prompt(courseCode, professorName, courseReviews, professorReviews)));
            if (!isCompleteSummary(text)) {
                text = cleanSummary(callGemini(apiKey, compactPrompt(courseCode, professorName, courseReviews, professorReviews, confidence)));
            }
            if (!isCompleteSummary(text)) {
                return Optional.empty();
            }
            if (text.isBlank()) {
                return Optional.empty();
            }
            SummaryResult result = new SummaryResult(text, confidence);
            summaryCache.put(cacheKey, result);
            return Optional.of(result);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (IOException exception) {
            return Optional.empty();
        }
    }

    private String callGemini(String apiKey, String prompt) throws IOException, InterruptedException {
        String model = configValue("GEMINI_MODEL").orElse(DEFAULT_MODEL);
        if (!model.startsWith("models/")) {
            model = "models/" + model;
        }

        Map<String, Object> body = Map.of(
                "contents", List.of(Map.of(
                        "role", "user",
                        "parts", List.of(Map.of("text", prompt))
                )),
                "generationConfig", Map.of(
                        "temperature", 0.2,
                        "maxOutputTokens", 2000
                )
        );

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://generativelanguage.googleapis.com/v1beta/" + model + ":generateContent"))
                .timeout(Duration.ofSeconds(25))
                .header("Content-Type", "application/json")
                .header("x-goog-api-key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            return "";
        }

        JsonNode parts = objectMapper.readTree(response.body())
                .path("candidates")
                .path(0)
                .path("content")
                .path("parts");
        StringBuilder text = new StringBuilder();
        if (parts.isArray()) {
            for (JsonNode part : parts) {
                text.append(part.path("text").asText(""));
            }
        }
        return text.toString().trim();
    }

    private Optional<String> configValue(String key) {
        String environmentValue = System.getenv(key);
        if (environmentValue != null && !environmentValue.isBlank()) {
            return Optional.of(environmentValue.trim());
        }

        if (!Files.exists(DOTENV_PATH)) {
            return Optional.empty();
        }

        try {
            return Files.readAllLines(DOTENV_PATH)
                    .stream()
                    .map(String::trim)
                    .filter(line -> !line.isBlank() && !line.startsWith("#"))
                    .map(line -> line.startsWith("export ") ? line.substring("export ".length()).trim() : line)
                    .filter(line -> line.startsWith(key + "="))
                    .map(line -> line.substring((key + "=").length()).trim())
                    .map(this::stripQuotes)
                    .filter(value -> !value.isBlank())
                    .findFirst();
        } catch (IOException exception) {
            return Optional.empty();
        }
    }

    private String stripQuotes(String value) {
        if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private String prompt(
            String courseCode,
            String professorName,
            List<Review> courseReviews,
            List<Review> professorReviews
    ) {
        return """
                You are summarizing university course/professor review evidence for students.

                Important rules:
                - There are NO direct reviews for this exact course/professor combination.
                - Use only the provided review evidence.
                - Do not invent course policies, assignment formats, grading schemes, exam formats, or teaching traits.
                - Clearly separate course-level evidence from professor-level evidence.
                - Use cautious language like "likely", "based on course reviews", and "based on professor reviews".
                - If there is not enough evidence for a category, say so.
                - Keep the summary concise and student-facing.
                - Make sure the confidence level is low, medium or high.
                - Write plain text only. Do not use Markdown bold, tables, or stray asterisks.
                - Finish all seven sections. Do not stop after the caveat.

                Course: %s
                Professor: %s

                Course review count: %d
                Professor review count: %d

                Course-level evidence:
                %s

                Professor-level evidence:
                %s

                Write a summary with these sections:
                1. Overall caveat
                2. Course difficulty and workload
                3. Assignments and grading
                4. Exams and tests
                5. Lecture quality
                6. Professor teaching style
                7. Confidence level
                """.formatted(
                courseCode,
                professorName,
                courseReviews.size(),
                professorReviews.size(),
                formatEvidence(courseReviews),
                formatEvidence(professorReviews)
        );
    }

    private String compactPrompt(
            String courseCode,
            String professorName,
            List<Review> courseReviews,
            List<Review> professorReviews,
            String confidence
    ) {
        return """
                Summarize likely student experience for this unseen UofT course/professor combination.
                There are no direct reviews for this exact combination, so use only the evidence below.
                Do not invent facts. Use cautious language. Plain text only.

                Course: %s
                Professor: %s
                Course review count: %d
                Professor review count: %d
                Confidence: %s

                Course-level evidence:
                %s

                Professor-level evidence:
                %s

                Return exactly this format, with 1 concise sentence per section:
                1. Overall caveat:
                2. Course difficulty and workload:
                3. Assignments and grading:
                4. Exams and tests:
                5. Lecture quality:
                6. Professor teaching style:
                7. Confidence level:
                """.formatted(
                courseCode,
                professorName,
                courseReviews.size(),
                professorReviews.size(),
                confidence,
                formatEvidence(courseReviews),
                formatEvidence(professorReviews)
        );
    }

    private String formatEvidence(List<Review> reviews) {
        List<Review> evidence = topRelevantReviews(reviews, 12);
        if (evidence.isEmpty()) {
            return "No written review evidence available.";
        }

        List<String> chunks = new ArrayList<>();
        int total = 0;
        for (Review review : evidence) {
            String text = formatReview(review);
            if (total + text.length() > 6000) {
                break;
            }
            chunks.add(text);
            total += text.length();
        }
        return String.join("\n", chunks);
    }

    private String formatReview(Review review) {
        String comment = review.comment().replaceAll("\\s+", " ").trim();
        if (comment.length() > 450) {
            comment = comment.substring(0, 447) + "...";
        }
        return "- Course: %s; Professor: %s; Quality: %s/5; Difficulty: %s/5; Grade: %s; Comment: %s"
                .formatted(
                        review.courseCode(),
                        review.professorName(),
                        displayNumber(review.quality()),
                        displayNumber(review.difficulty()),
                        review.grade(),
                        comment
                );
    }

    private boolean isCompleteSummary(String text) {
        String normalized = text.toLowerCase(Locale.ROOT);
        return normalized.contains("1.")
                && normalized.contains("2.")
                && normalized.contains("3.")
                && normalized.contains("4.")
                && normalized.contains("5.")
                && normalized.contains("6.")
                && normalized.contains("7.")
                && normalized.contains("confidence");
    }

    private String cleanSummary(String text) {
        return text.replace("**", "")
                .replaceAll("(?m)^\\s*\\*\\s*$", "")
                .trim();
    }

    private List<Review> topRelevantReviews(List<Review> reviews, int limit) {
        List<Review> candidates = reviews.stream()
                .filter(review -> review.comment() != null && review.comment().trim().length() > 20)
                .toList();
        if (candidates.isEmpty()) {
            return List.of();
        }

        List<List<String>> documents = candidates.stream()
                .map(review -> vectorTokens(review.comment()))
                .collect(Collectors.toCollection(ArrayList::new));
        List<String> queryTokens = vectorTokens(ASPECT_QUERY);
        documents.add(queryTokens);

        Map<String, Integer> documentFrequency = new HashMap<>();
        for (List<String> document : documents) {
            for (String token : new HashSet<>(document)) {
                documentFrequency.merge(token, 1, Integer::sum);
            }
        }

        int documentCount = documents.size();
        Map<String, Double> queryVector = tfidfVector(queryTokens, documentFrequency, documentCount);

        return candidates.stream()
                .map(review -> Map.entry(review, cosine(queryVector, tfidfVector(vectorTokens(review.comment()), documentFrequency, documentCount))))
                .sorted(Map.Entry.<Review, Double>comparingByValue().reversed())
                .limit(limit)
                .map(Map.Entry::getKey)
                .toList();
    }

    private Map<String, Double> tfidfVector(List<String> terms, Map<String, Integer> documentFrequency, int documentCount) {
        Map<String, Long> counts = terms.stream()
                .collect(Collectors.groupingBy(term -> term, Collectors.counting()));
        Map<String, Double> vector = new HashMap<>();
        counts.forEach((term, count) -> {
            double idf = Math.log((documentCount + 1.0) / (documentFrequency.getOrDefault(term, 0) + 1.0)) + 1.0;
            vector.put(term, count * idf);
        });
        return vector;
    }

    private double cosine(Map<String, Double> left, Map<String, Double> right) {
        double dot = 0;
        for (Map.Entry<String, Double> entry : left.entrySet()) {
            dot += entry.getValue() * right.getOrDefault(entry.getKey(), 0.0);
        }
        double leftNorm = Math.sqrt(left.values().stream().mapToDouble(value -> value * value).sum());
        double rightNorm = Math.sqrt(right.values().stream().mapToDouble(value -> value * value).sum());
        if (leftNorm == 0 || rightNorm == 0) {
            return 0;
        }
        return dot / (leftNorm * rightNorm);
    }

    private String confidenceLabel(int courseReviewCount, int professorReviewCount) {
        if (courseReviewCount >= 10 && professorReviewCount >= 10) {
            return "Medium";
        }
        return "Low";
    }

    private String displayNumber(Double value) {
        return value == null || Double.isNaN(value) ? "n/a" : "%.1f".formatted(value);
    }

    private static Set<String> tokens(String text) {
        Set<String> result = new HashSet<>();
        for (String token : TOKEN_SPLIT.split(text.toLowerCase(Locale.ROOT))) {
            if (token.length() > 2) {
                result.add(token);
            }
        }
        return result;
    }

    private static List<String> vectorTokens(String text) {
        List<String> result = new ArrayList<>();
        for (String token : TOKEN_SPLIT.split(text.toLowerCase(Locale.ROOT))) {
            if (token.length() > 2 && !STOP_WORDS.contains(token)) {
                result.add(token);
            }
        }
        return result;
    }
}
