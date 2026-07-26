import pandas as pd


def get_course_stats(course_code, prof_id=None, df=None):
    if df is None:
        df = pd.read_csv("cleaned_reviews.csv")

    # Filter by course code
    course_data = df[df["normalized_course_code"] == course_code.upper()]
    if prof_id:
        course_data = course_data[course_data["rmp_professor_id"] == str(prof_id)]

    if course_data.empty:
        return None

    stats = {
        "course_code": course_code.upper(),
        "professor_id": prof_id,
        "num_reviews": len(course_data),
        "avg_difficulty": round(course_data["difficulty"].mean(), 2),
        "avg_quality": round(course_data["quality"].mean(), 2),
        "take_again_yes_ratio": round((course_data["would_take_again"] == "Yes").mean(), 2)
            if "would_take_again" in course_data else None,
        "textbook_yes_ratio": round((course_data["textbook"] == "Yes").mean(), 2)
            if "textbook" in course_data else None,
        "representative_comment": course_data["comment"].iloc[course_data["comment"].str.len().idxmax()]
    }

    return stats
