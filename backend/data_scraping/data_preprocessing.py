import pandas as pd
import re
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.model_selection import train_test_split
import numpy as np


def normalize_course_code(code):
    if not isinstance(code, str):
        return None
    code = code.strip().upper()

    # Remove non-alphanumeric characters and collapse spacing
    code = re.sub(r'[^A-Z0-9]', '', code)

    department_fixes = {
        'MATH': 'MAT',
        'COMP': 'CSC',
        'STAT': 'STA',
        'PHIL': 'PHL',
        'FREN': 'FSL',
        'GERM': 'GER',
        'ENVR': 'ENV',
    }

    # Match dept + full number (3 or more digits)
    match = re.match(r'^([A-Z]{3,4})(\d{3,})', code)
    if match:
        dept, number = match.groups()
        dept = department_fixes.get(dept, dept)
        return f"{dept}{number}"

    return None


# Apply to your dataset
if __name__ == "__main__":
    df = pd.read_csv("all_reviews.csv")
    df['normalized_course_code'] = df['course_code'].apply(normalize_course_code)
    # df.to_csv("all_reviews_normalized.csv", index=False)

    # Remove rows missing critical fields
    df = df[df['normalized_course_code'].notnull()]
    df = df[df['difficulty'].notnull()]
    df = df[df['quality'].notnull()]
    df = df[df['comment'].notnull() & df['comment'].str.strip().ne('')]

    invalid_codes = df[df['normalized_course_code'].str.len() < 6]

    # Display the invalid rows
    print(f"Found {len(invalid_codes)} invalid course codes out of {len(df)}:")
    print(invalid_codes[['course_code', 'normalized_course_code']])

    df.to_csv("cleaned_reviews.csv", index=False)
