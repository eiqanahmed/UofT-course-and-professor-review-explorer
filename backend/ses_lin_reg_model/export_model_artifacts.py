import json
from pathlib import Path

import joblib
import numpy as np


def export_model(preprocessor_path, weights_path, output_path):
    preprocessor = joblib.load(preprocessor_path)
    weights = np.load(weights_path)

    text = preprocessor.named_transformers_["text"]
    categorical = preprocessor.named_transformers_["categorical"]
    numeric = preprocessor.named_transformers_["numeric"]

    vocab_size = len(text.vocabulary_)
    categorical_sizes = [len(categories) for categories in categorical.categories_]
    categorical_size = sum(categorical_sizes)
    numeric_size = len(numeric.mean_)

    expected = vocab_size + categorical_size + numeric_size + 1
    if len(weights) != expected:
        raise ValueError(f"{weights_path} has {len(weights)} weights, expected {expected}")

    text_weights = weights[:vocab_size]
    categorical_start = vocab_size
    numeric_start = categorical_start + categorical_size
    numeric_weights = weights[numeric_start:numeric_start + numeric_size]

    categorical_blocks = []
    cursor = categorical_start
    for feature_name, categories in zip(categorical.feature_names_in_, categorical.categories_):
        size = len(categories)
        categorical_blocks.append({
            "feature": feature_name,
            "categories": [str(category) for category in categories],
            "weights": weights[cursor:cursor + size].tolist(),
        })
        cursor += size

    payload = {
        "vocabulary": {token: int(index) for token, index in text.vocabulary_.items()},
        "idf": text.idf_.tolist(),
        "stopWords": sorted(text.get_stop_words() or []),
        "textWeights": text_weights.tolist(),
        "categorical": categorical_blocks,
        "numeric": {
            "features": list(numeric.feature_names_in_),
            "mean": numeric.mean_.tolist(),
            "scale": numeric.scale_.tolist(),
            "weights": numeric_weights.tolist(),
        },
        "bias": float(weights[-1]),
    }

    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(json.dumps(payload, separators=(",", ":")))


if __name__ == "__main__":
    base = Path(__file__).resolve().parent
    resources = base.parent / "src" / "main" / "resources" / "models"
    export_model(base / "preprocessor.pkl", base / "linear_model_weights.npy", resources / "student_experience_prof.json")
    export_model(base / "preprocessor_noprof.pkl", base / "linear_model_weights_noprof.npy", resources / "student_experience_course.json")
