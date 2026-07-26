import pandas as pd
import numpy as np
from sklearn.model_selection import train_test_split
from sklearn.preprocessing import OneHotEncoder, StandardScaler
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.compose import ColumnTransformer
from sklearn.pipeline import make_pipeline
import matplotlib.pyplot as plt
import joblib


### ========== Gradient Descent Utilities ==========

def add_bias(X):
    return np.hstack((X, np.ones((X.shape[0], 1))))


def pred(w, X):
    return X @ w


def mse(w, X, t):
    y = pred(w, X)
    return np.mean((y - t) ** 2) / 2


def grad(w, X, t, lambda_reg=0.1):
    y = pred(w, X)
    return (X.T @ (y - t)) / len(t) + 2 * lambda_reg * w


def solve_via_gradient_descent(alpha, niter, X_train, t_train, X_valid, t_valid, lambda_reg=0.1):
    w = np.zeros(X_train.shape[1])
    train_mses, valid_mses = [], []

    for it in range(niter):
        w -= alpha * grad(w, X_train, t_train, lambda_reg)
        train_mses.append(mse(w, X_train, t_train))
        valid_mses.append(mse(w, X_valid, t_valid))

        if it % 100 == 0:
            print(f"Iter {it}: Train MSE = {train_mses[-1]:.4f}, Valid MSE = {valid_mses[-1]:.4f}")

    return w, train_mses, valid_mses


### ========== Training Script ==========

if __name__ == "__main__":
    df = pd.read_csv("../data_scraping/cleaned_reviews.csv")

    # Clean + filter
    df = df.dropna(subset=["comment", "quality", "difficulty"])
    df["thumbs_up"] = df["thumbs_up"].fillna(0)
    df["thumbs_down"] = df["thumbs_down"].fillna(0)
    df["grade"] = df["grade"].fillna("Unknown")

    # Professor-level features
    prof_stats = df.groupby("prof_id").agg(
        prof_avg_quality=("quality", "mean"),
        prof_avg_difficulty=("difficulty", "mean"),
        prof_review_count=("quality", "count")
    ).reset_index()

    df = df.merge(prof_stats, on="prof_id", how="left")

    # Inputs and target
    features = [
        "comment", "course_code", "prof_id", "difficulty", "thumbs_up", "thumbs_down", "grade",
        "prof_avg_quality", "prof_avg_difficulty", "prof_review_count"
    ]

    X = df[features]
    # y = df["quality"]
    neutral_difficulty = 3.5
    alpha = 0.5
    # df = df.dropna(subset=["quality", "difficulty"])  # Must drop rows with missing difficulty now

    # alpha is is a weight (e.g., 0.5) that controls how much difficulty influences experience
    df["experience_score"] = df["quality"] - alpha * (df["difficulty"] - neutral_difficulty)
    y = df["experience_score"]

    # Split
    X_train_raw, X_valid_raw, y_train, y_valid = train_test_split(X, y, test_size=0.2, random_state=42)

    # Preprocessing
    text = ("text", TfidfVectorizer(max_features=5000, stop_words="english"), "comment")
    categorical = ("categorical", OneHotEncoder(handle_unknown="ignore"), ["course_code", "prof_id", "grade"])
    numeric = (
        "numeric",
        StandardScaler(),
        ["difficulty", "thumbs_up", "thumbs_down", "prof_avg_quality", "prof_avg_difficulty", "prof_review_count"]
    )

    preprocessor = ColumnTransformer(
        transformers=[
            text,
            categorical,
            numeric
        ]
    )

    # Fit and transform
    X_train_vec = preprocessor.fit_transform(X_train_raw).toarray()
    X_valid_vec = preprocessor.transform(X_valid_raw).toarray()

    # Add bias
    X_train_vec = add_bias(X_train_vec)
    X_valid_vec = add_bias(X_valid_vec)

    # Train
    w, train_mses, valid_mses = solve_via_gradient_descent(
        alpha=0.0025,
        niter=1500,
        X_train=X_train_vec,
        t_train=y_train,
        X_valid=X_valid_vec,
        t_valid=y_valid,
        lambda_reg=0.2
    )

    np.save("linear_model_weights.npy", w)
    joblib.dump(preprocessor, "preprocessor.pkl")

    # Plot
    plt.plot(train_mses, label="Train MSE")
    plt.plot(valid_mses, label="Valid MSE")
    plt.xlabel("Iteration")
    plt.ylabel("MSE")
    plt.title("Training Progress")
    plt.legend()
    plt.show()

