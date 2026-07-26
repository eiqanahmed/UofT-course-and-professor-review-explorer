import pandas as pd
import numpy as np
import re
import joblib


def get_course_only_score(df, course_code, min_reviews=3):
    course_reviews_gcos = df[df["normalized_course_code"] == course_code]

    if len(course_reviews_gcos) >= min_reviews:
        return round(course_reviews_gcos["quality"].mean(), 2)
    else:
        return None  # Fallback required


# Function for if the course/prof combo exists and has more than 3 reviews
def load_lookup_table(csv_path, min_reviews=3, neutral_difficulty=3.5, alpha=0.5):
    df = pd.read_csv(csv_path)
    df = df.dropna(subset=["quality", "difficulty"])

    grouped = df.groupby(["normalized_course_code", "prof_id"])
    stats = grouped.agg({
        "quality": "mean",
        "difficulty": "mean",
        "comment": "count"
    }).reset_index().rename(columns={"comment": "count"})

    eligible = stats[stats["count"] >= min_reviews]

    # Compute experience score
    def compute_experience(row):
        return round(row["quality"] - alpha * (row["difficulty"] - neutral_difficulty), 2)

    eligible["experience"] = eligible.apply(compute_experience, axis=1)

    # Return a lookup dictionary: {(course, prof_id): experience_score}
    return {
        (row["normalized_course_code"], row["prof_id"]): row["experience"]
        for _, row in eligible.iterrows()
    }


def get_lookup_score(lookup_dict, course_code, prof_id):
    return lookup_dict.get((course_code, prof_id), None)


def normalize_name(name):
    """
    Normalize professor name for consistent lookup.
    Handles CamelCase, extra spaces, and casing.
    """
    # Insert space before any capital letter not at the start
    name_with_spaces = re.sub(r'(?<!^)(?=[A-Z])', ' ', name)
    # Normalize whitespace and lowercase everything
    return ' '.join(name_with_spaces.strip().split()).lower()


def build_prof_name_to_id_map(csv_path="professor_data.csv"):
    """
    Builds a dictionary mapping lowercase, space-normalized professor names to their RMP IDs.
    """
    df = pd.read_csv(csv_path)
    df = df.dropna(subset=["name", "rmp_professor_id"])

    # Fix spacing in names like "JaneForner" -> "Jane Forner"
    df["name_fixed"] = df["name"].apply(normalize_name)
    df["name_cleaned"] = df["name_fixed"].str.strip().str.lower()

    return dict(zip(df["name_cleaned"], df["rmp_professor_id"]))


if __name__ == "__main__":

    course = "MAT246"
    prof = "Ali Feizmohammadi"

    normalized_course = course.strip().upper()

    df = pd.read_csv("../data_scraping/cleaned_reviews.csv")

    if prof != "":  # =========== CASE: course + prof ===========

        lookup = load_lookup_table("../data_scraping/cleaned_reviews.csv")
        name_to_id = build_prof_name_to_id_map("../data_scraping/professor_data.csv")
        normalized = normalize_name(prof).strip().lower()
        prof_id = name_to_id.get(normalized)

        print("Normalized name:", normalized)
        print("Prof ID found:", prof_id)

        score = get_lookup_score(lookup, normalized_course, prof_id)

        if score is not None:
            print(f"Lookup-based student experience score: {score}/5")
        else:
            w = np.load("linear_model_weights.npy")
            preprocessor = joblib.load("preprocessor.pkl")

            prof_reviews = df[df["prof_id"] == prof_id]
            course_reviews = df[df["normalized_course_code"] == normalized_course]

            if prof_reviews.empty and course_reviews.empty:
                print("No data available for fallback prediction.")
            else:
                combined = pd.concat([prof_reviews, course_reviews], ignore_index=True).drop_duplicates()
                combined["difficulty"] = combined["difficulty"].fillna(combined["difficulty"].mean())
                combined["thumbs_up"] = combined["thumbs_up"].fillna(0)
                combined["thumbs_down"] = combined["thumbs_down"].fillna(0)
                combined["grade"] = combined["grade"].fillna("Unknown")
                combined["comment"] = combined["comment"].fillna("")

                aggregated_input = {
                    "comment": " ".join(combined["comment"].astype(str).tolist()),
                    "course_code": course,
                    "prof_id": prof_id,
                    "difficulty": combined["difficulty"].mean(),
                    "thumbs_up": combined["thumbs_up"].mean(),
                    "thumbs_down": combined["thumbs_down"].mean(),
                    "grade": combined["grade"].mode()[0] if not combined["grade"].mode().empty else "Unknown",
                    "prof_avg_quality": prof_reviews["quality"].mean() if not prof_reviews.empty else df["quality"].mean(),
                    "prof_avg_difficulty": prof_reviews["difficulty"].mean() if not prof_reviews.empty else df["difficulty"].mean(),
                    "prof_review_count": len(prof_reviews)
                }

                new_data = pd.DataFrame([aggregated_input])
                X_vec = preprocessor.transform(new_data).toarray()
                X_vec = np.hstack((X_vec, np.ones((X_vec.shape[0], 1))))
                y_pred = X_vec @ w
                score = np.clip(y_pred[0], 0, 5)
                print(f"Predicted student experience score: {score:.2f}/5")

    else:  # =========== CASE: course only ===========

        course_reviews = df[df["normalized_course_code"] == course]

        if course_reviews.empty:
            print("No reviews found for that course.")
        else:
            w = np.load("linear_model_weights_noprof.npy")
            preprocessor = joblib.load("preprocessor_noprof.pkl")

            course_reviews["difficulty"] = course_reviews["difficulty"].fillna(course_reviews["difficulty"].mean())
            course_reviews["thumbs_up"] = course_reviews["thumbs_up"].fillna(0)
            course_reviews["thumbs_down"] = course_reviews["thumbs_down"].fillna(0)
            course_reviews["grade"] = course_reviews["grade"].fillna("Unknown")
            course_reviews["comment"] = course_reviews["comment"].fillna("")

            aggregated_input = {
                "comment": " ".join(course_reviews["comment"].astype(str).tolist()),
                "course_code": course,
                "difficulty": course_reviews["difficulty"].mean(),
                "thumbs_up": course_reviews["thumbs_up"].mean(),
                "thumbs_down": course_reviews["thumbs_down"].mean(),
                "grade": course_reviews["grade"].mode()[0] if not course_reviews["grade"].mode().empty else "Unknown"
            }

            new_data = pd.DataFrame([aggregated_input])
            X_vec = preprocessor.transform(new_data).toarray()
            X_vec = np.hstack((X_vec, np.ones((X_vec.shape[0], 1))))
            y_pred = X_vec @ w
            score = np.clip(y_pred[0], 0, 5)
            print(f"Predicted student experience score (course-only model): {score:.2f}/5")