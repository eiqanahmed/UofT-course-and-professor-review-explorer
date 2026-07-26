# UofT Course and Professor Review Explorer

Full-stack app for searching and comparing UofT courses and professors using the local `cleaned_reviews.csv` and `professor_data.csv` datasets.

## Project Layout

```text
backend/   Spring Boot API, CSV data, scraping scripts, existing ML artifacts
frontend/  Next.js + React + TypeScript + TailwindCSS UI
```

## Run The App

Start the backend:

```bash
cd backend
mvn spring-boot:run
```

Start the frontend in a second terminal:

```bash
cd frontend
npm install
npm run dev
```

Then open:

```text
http://localhost:3000
```

The Next.js frontend rewrites `/api` requests to the Spring Boot backend at `http://localhost:8080`.
For deployment, set `API_BASE_URL` in the frontend environment to the deployed backend URL.

## Backend API

- `GET /api/overview`
- `GET /api/search?q=organized professor easy exams`
- `GET /api/courses/{courseCode}`
- `GET /api/professors/{professorId}`
- `GET /api/reviews?course=MAT246&rating_min=4&q=clear lectures`

## Features

- Course and professor search.
- Same-course professor comparison.
- Average quality, difficulty, grades, attendance requirement rate, take-again rate, and review count.
- Review filters for course, professor, grade, difficulty, and rating.
- Natural-language review search using local TF-IDF cosine similarity.
- Representative positive, neutral, and negative reviews.
- Evidence themes for lectures, grading, workload, exams, and communication.
- Aspect sentiment and confidence indicators.
- Subject-aware Bayesian quality averages so tiny review counts do not dominate rankings.
