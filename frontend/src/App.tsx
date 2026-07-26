"use client";

import { ArrowLeft, GraduationCap, Search, Users } from "lucide-react";
import { useEffect, useMemo, useRef, useState } from "react";
import { getCourse, getOverview, getProfessor, searchEntities } from "./api";
import type {
  CombinationPrediction,
  CourseDetail,
  CourseSummary,
  Overview,
  ProfessorDetail,
  ProfessorSummary,
  Review,
} from "./types";

type Selection =
  | { kind: "course"; id: string; detail: CourseDetail }
  | { kind: "professor"; id: number; detail: ProfessorDetail };

const emptyOverview: Overview = {
  totals: { reviews: 0, courses: 0, professors: 0 },
  topCourses: [],
  topProfessors: [],
  grades: [],
};

function format(value: number | null | undefined, suffix = "") {
  return value === null || value === undefined || Number.isNaN(value) ? "n/a" : `${value}${suffix}`;
}

function confidenceClass(label?: string) {
  if (label === "High") return "bg-emerald-50 text-emerald-700 border-emerald-200";
  if (label === "Medium") return "bg-amber-50 text-amber-700 border-amber-200";
  return "bg-rose-50 text-rose-700 border-rose-200";
}

function sentimentClass(label: string) {
  if (label === "Positive") return "bg-emerald-600";
  if (label === "Negative") return "bg-rose-600";
  return "bg-amber-500";
}

function sentimentTextClass(label: string) {
  if (label === "Positive") return "text-emerald-700";
  if (label === "Negative") return "text-rose-700";
  return "text-amber-700";
}

function Badge({ children, label }: { children: React.ReactNode; label?: string }) {
  return (
    <span className={`inline-flex min-h-6 items-center rounded-full border px-2 text-xs font-bold ${confidenceClass(label)}`}>
      {children}
    </span>
  );
}

function ResultCard({
  item,
  kind,
  onOpen,
}: {
  item: CourseSummary | ProfessorSummary;
  kind: "course" | "professor";
  onOpen: () => void;
}) {
  const isCourse = kind === "course";
  const title = isCourse ? (item as CourseSummary).courseCode : (item as ProfessorSummary).professorName;
  const subtitle = isCourse
    ? `${(item as CourseSummary).professorCount} professor${(item as CourseSummary).professorCount === 1 ? "" : "s"}`
    : `${(item as ProfessorSummary).department} · ${(item as ProfessorSummary).courseCount} course${
        (item as ProfessorSummary).courseCount === 1 ? "" : "s"
      }`;
  const scoreLabel = isCourse ? "Student Experience Score (/5)" : "Professor Quality (/5)";

  return (
    <button
      type="button"
      onClick={onOpen}
      className="w-full rounded-lg border border-line bg-white p-3 text-left transition hover:border-uoft hover:shadow-sm"
    >
      <h3 className="text-sm font-bold text-ink">{title}</h3>
      <div className="mt-2 flex flex-wrap gap-2 text-xs text-muted">
        <span>{subtitle}</span>
        <span>
          {scoreLabel} {format(item.experienceScore)}
        </span>
        <span>Difficulty (/5) {format(item.avgDifficulty)}</span>
      </div>
      <div className="mt-3 flex flex-wrap items-center gap-2">
        <Badge label={item.confidence.label}>{item.confidence.label} confidence</Badge>
        <span className="text-xs text-muted">{item.reviewCount} reviews</span>
      </div>
    </button>
  );
}

function ReviewCard({ review, label }: { review: Review | null; label?: string }) {
  if (!review) return null;
  return (
    <article className="rounded-lg border border-line bg-white p-4">
      {label ? <Badge>{label}</Badge> : null}
      <h3 className="mt-2 text-sm font-bold text-ink">
        {review.courseCode} · {review.professorName}
      </h3>
      <div className="mt-2 flex flex-wrap gap-2 text-xs text-muted">
        <span>{review.date}</span>
        <span>Quality {format(review.quality)}</span>
        <span>Difficulty {format(review.difficulty)}</span>
        <span>Grade {review.grade}</span>
      </div>
      <p className="mt-3 text-sm leading-6 text-ink">{review.comment || "No written comment."}</p>
    </article>
  );
}

function Metric({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-lg border border-line bg-white p-4">
      <span className="block text-xs font-semibold text-muted">{label}</span>
      <strong className="mt-1 block text-2xl text-ink">{value}</strong>
    </div>
  );
}

export function App() {
  const [overview, setOverview] = useState<Overview>(emptyOverview);
  const [courses, setCourses] = useState<CourseSummary[]>([]);
  const [professors, setProfessors] = useState<ProfessorSummary[]>([]);
  const [combinationPrediction, setCombinationPrediction] = useState<CombinationPrediction | null>(null);
  const [selection, setSelection] = useState<Selection | null>(null);
  const [selectionHistory, setSelectionHistory] = useState<Selection[]>([]);
  const [query, setQuery] = useState("");
  const [courseFilter, setCourseFilter] = useState("");
  const [professorFilter, setProfessorFilter] = useState("");
  const [difficultyMin, setDifficultyMin] = useState("");
  const [experienceMin, setExperienceMin] = useState("");
  const [error, setError] = useState<string | null>(null);
  const hasSearchedRef = useRef(false);
  const searchRequestIdRef = useRef(0);

  useEffect(() => {
    getOverview()
      .then((data) => {
        setOverview(data);
        if (!hasSearchedRef.current) {
          setCourses(data.topCourses);
          setProfessors(data.topProfessors);
        }
      })
      .catch((err: Error) => setError(err.message));
  }, []);

  async function runSearch() {
    hasSearchedRef.current = true;
    const requestId = searchRequestIdRef.current + 1;
    searchRequestIdRef.current = requestId;
    const params = searchParams("12");
    const data = await searchEntities(params);
    if (requestId !== searchRequestIdRef.current) {
      return;
    }
    setCourses(data.courses);
    setProfessors(data.professors);
    setCombinationPrediction(data.combinationPrediction ?? null);
    setSelection(null);
    setSelectionHistory([]);
  }

  function searchParams(limit = "60") {
    const params = new URLSearchParams();
    params.set("q", query);
    params.set("limit", limit);
    if (courseFilter) params.set("course", courseFilter);
    if (professorFilter) params.set("professor", professorFilter);
    if (difficultyMin) params.set("difficulty_min", difficultyMin);
    if (experienceMin) params.set("experience_min", experienceMin);
    return params;
  }

  async function openCourse(courseCode: string, preserveContext = false) {
    if (preserveContext && selection) {
      setSelectionHistory((history) => [...history, selection]);
    } else {
      setSelectionHistory([]);
    }
    setCombinationPrediction(null);
    setSelection({ kind: "course", id: courseCode, detail: await getCourse(courseCode) });
  }

  async function openProfessor(professorId: number, preserveContext = false) {
    if (preserveContext && selection) {
      setSelectionHistory((history) => [...history, selection]);
    } else {
      setSelectionHistory([]);
    }
    setCombinationPrediction(null);
    setSelection({ kind: "professor", id: professorId, detail: await getProfessor(professorId) });
  }

  function goBackSelection() {
    setSelectionHistory((history) => {
      const previous = history.at(-1);
      if (previous) {
        setSelection(previous);
      }
      return history.slice(0, -1);
    });
  }

  const selectedDetail = selection?.detail;
  const comparisonRows = useMemo(() => {
    if (!selection) return [];
    return selection.kind === "course" ? selection.detail.professors : selection.detail.courses;
  }, [selection]);

  return (
    <main className="mx-auto min-h-screen w-full max-w-[1440px] bg-paper p-4 text-ink md:p-6">
      <header className="grid gap-5 pb-6 lg:grid-cols-[1fr_auto] lg:items-end">
        <div>
          <p className="mb-2 text-xs font-extrabold uppercase tracking-wider text-uoft">University of Toronto</p>
          <h1 className="text-3xl font-extrabold leading-tight md:text-4xl">Course and Professor Review Explorer</h1>
        </div>
        <div className="grid grid-cols-3 gap-2">
          <Metric label="Reviews" value={String(overview.totals.reviews)} />
          <Metric label="Courses" value={String(overview.totals.courses)} />
          <Metric label="Professors" value={String(overview.totals.professors)} />
        </div>
      </header>

      {error ? <div className="mb-4 rounded-lg border border-rose-200 bg-rose-50 p-3 text-sm text-rose-700">{error}</div> : null}

      <section className="rounded-lg border border-line bg-white p-4">
        <h2 className="mb-2 text-sm font-bold text-ink">General Search</h2>
        <div className="grid gap-2 md:grid-cols-[1fr_auto]">
          <div className="relative">
            <Search className="pointer-events-none absolute left-3 top-3 h-5 w-5 text-muted" />
            <input
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              onKeyDown={(event) => event.key === "Enter" && runSearch()}
              className="h-11 w-full rounded-md border border-line pl-10 pr-3 outline-none focus:border-uoft"
              placeholder="Search for courses, professors, reviews, or phrases like 'courses with easy exams'"
            />
          </div>
          <button onClick={runSearch} className="inline-flex h-11 items-center justify-center gap-2 rounded-md bg-uoft px-4 font-bold text-white">
            <Search className="h-4 w-4" />
            Search
          </button>
        </div>
        <h2 className="mb-2 mt-4 text-sm font-bold text-ink">Detailed Search</h2>
        <div className="grid gap-8 md:grid-cols-2">
          <input
            value={courseFilter}
            onChange={(event) => setCourseFilter(event.target.value)}
            onKeyDown={(event) => event.key === "Enter" && runSearch()}
            className="h-10 rounded-md border border-line px-3"
            placeholder="Course code"
          />
          <input
            value={professorFilter}
            onChange={(event) => setProfessorFilter(event.target.value)}
            onKeyDown={(event) => event.key === "Enter" && runSearch()}
            className="h-10 rounded-md border border-line px-3"
            placeholder="Professor name"
          />
          {/* <input
            value={difficultyMin}
            onChange={(event) => setDifficultyMin(event.target.value)}
            onKeyDown={(event) => event.key === "Enter" && runSearch()}
            className="h-10 rounded-md border border-line px-3"
            placeholder="Min difficulty (/5)"
          />
          <input
            value={experienceMin}
            onChange={(event) => setExperienceMin(event.target.value)}
            onKeyDown={(event) => event.key === "Enter" && runSearch()}
            className="h-10 rounded-md border border-line px-3"
            placeholder="Min student experience score (/5)"
          /> */}
        </div>
      </section>

      <div className="my-4 grid gap-4 lg:grid-cols-[360px_1fr]">
        <aside className="grid content-start gap-4">
          <section className="rounded-lg border border-line bg-white p-4">
            <h2 className="mb-3 flex items-center gap-2 text-lg font-bold">
              <GraduationCap className="h-5 w-5 text-uoft" />
              Courses
            </h2>
            <div className="grid gap-3">
              {courses.length ? (
                courses.map((course) => <ResultCard key={course.courseCode} item={course} kind="course" onOpen={() => openCourse(course.courseCode)} />)
              ) : (
                <p className="text-sm text-muted">No reviews available.</p>
              )}
            </div>
          </section>
          <section className="rounded-lg border border-line bg-white p-4">
            <h2 className="mb-3 flex items-center gap-2 text-lg font-bold">
              <Users className="h-5 w-5 text-uoft" />
              Professors
            </h2>
            <div className="grid gap-3">
              {professors.length ? (
                professors.map((professor) => <ResultCard key={professor.professorId} item={professor} kind="professor" onOpen={() => openProfessor(professor.professorId)} />)
              ) : (
                <p className="text-sm text-muted">No matching professors.</p>
              )}
            </div>
          </section>
        </aside>

        <section className="rounded-lg border border-line bg-white p-4">
          {!selectedDetail ? (
            <div>
              {combinationPrediction ? (
                <div className="grid gap-4">
                  <div>
                    <h2 className="text-lg font-bold">
                      {combinationPrediction.courseCode} with {combinationPrediction.professorName}
                    </h2>
                    <p className="mt-1 text-sm text-muted">{combinationPrediction.department}</p>
                    <p className="mt-2 text-sm text-muted">{combinationPrediction.reason}</p>
                  </div>
                  <div className="grid gap-3 md:grid-cols-3">
                    <Metric label="Predicted Student Experience Score (/5)" value={format(combinationPrediction.experienceScore)} />
                    <Metric label="Course reviews" value={String(combinationPrediction.courseReviewCount)} />
                    <Metric label="Professor reviews" value={String(combinationPrediction.professorReviewCount)} />
                  </div>
                </div>
              ) : (
                <>
                  <h2 className="text-lg font-bold">Pick a course or professor</h2>
                  <p className="mt-2 text-sm text-muted">Compare ratings, difficulty, review evidence, themes, and confidence before choosing.</p>
                </>
              )}
            </div>
          ) : (
            <div className="grid gap-5">
              <div className="flex flex-wrap items-start justify-between gap-3">
                <div>
                  {selectionHistory.length ? (
                    <button
                      type="button"
                      onClick={goBackSelection}
                      className="mb-3 inline-flex items-center gap-2 rounded-md bg-slate-100 px-3 py-2 text-sm font-bold text-ink"
                    >
                      <ArrowLeft className="h-4 w-4" />
                      Back to comparison
                    </button>
                  ) : null}
                  <h2 className="text-xl font-extrabold">
                    {selection?.kind === "course" ? (selectedDetail as CourseDetail).courseCode : (selectedDetail as ProfessorDetail).professorName}
                  </h2>
                  <p className="mt-1 text-sm text-muted">{selectedDetail.confidence.reason}</p>
                  <p className="mt-1 text-sm text-muted">
                    {selection?.kind === "professor"
                      ? "Professor Quality uses Bayesian averaging across this professor's reviews, so very small review counts do not overstate the rating."
                      : "Student Experience Score uses observed review evidence when there are 15 or more reviews, and blends observed evidence with a model estimate when review counts are lower."}
                  </p>
                </div>
                <Badge label={selectedDetail.confidence.label}>{selectedDetail.confidence.label} confidence</Badge>
              </div>

              <div className="grid gap-3 md:grid-cols-4">
                <Metric label={selection?.kind === "professor" ? "Professor Quality (/5)" : "Student Experience Score (/5)"} value={format(selectedDetail.experienceScore)} />
                <Metric label="Difficulty (/5)" value={format(selectedDetail.avgDifficulty)} />
                <Metric label="Reviews" value={format(selectedDetail.reviewCount)} />
                <Metric label="Confidence" value={format(selectedDetail.confidence.score, "%")} />
              </div>

              <section>
                <h2 className="mb-3 text-lg font-bold">{selection?.kind === "course" ? "Compare professors" : "Courses taught"}</h2>
                <div className="overflow-x-auto">
                  <table className="w-full border-collapse text-left text-sm">
                    <thead className="text-xs uppercase text-muted">
                      <tr>
                        <th className="border-b border-line p-3">Name</th>
                        <th className="border-b border-line p-3">Student Experience Score (/5)</th>
                        <th className="border-b border-line p-3">Difficulty (/5)</th>
                        <th className="border-b border-line p-3">Reviews</th>
                        <th className="border-b border-line p-3">Confidence</th>
                        <th className="border-b border-line p-3"></th>
                      </tr>
                    </thead>
                    <tbody>
                      {comparisonRows.map((item) => {
                        const isCourse = "courseCode" in item;
                        return (
                          <tr key={isCourse ? item.courseCode : item.professorId}>
                            <td className="border-b border-line p-3">{isCourse ? item.courseCode : item.professorName}</td>
                            <td className="border-b border-line p-3">{format(item.experienceScore)}</td>
                            <td className="border-b border-line p-3">{format(item.avgDifficulty)}</td>
                            <td className="border-b border-line p-3">{item.reviewCount}</td>
                            <td className="border-b border-line p-3">
                              <Badge label={item.confidence.label}>{item.confidence.label}</Badge>
                            </td>
                            <td className="border-b border-line p-3">
                              <button
                                className="rounded-md bg-slate-100 px-3 py-2 text-xs font-bold text-ink"
                                onClick={() => (isCourse ? openCourse(item.courseCode, true) : openProfessor(item.professorId, true))}
                              >
                                Open
                              </button>
                            </td>
                          </tr>
                        );
                      })}
                    </tbody>
                  </table>
                </div>
              </section>

              <section>
                <h2 className="mb-3 text-lg font-bold">Representative reviews</h2>
                <div className="grid gap-3 xl:grid-cols-3">
                  <ReviewCard review={selectedDetail.representativeReviews.positive} label="Positive" />
                  <ReviewCard review={selectedDetail.representativeReviews.neutral} label="Neutral" />
                  <ReviewCard review={selectedDetail.representativeReviews.negative} label="Negative" />
                </div>
              </section>

              <section>
                <h2 className="mb-3 text-lg font-bold">Aspect sentiment</h2>
                {selectedDetail.aspects.length ? (
                  <div className="grid gap-2">
                    {selectedDetail.aspects.map((aspect) => (
                      <div key={aspect.aspect} className="grid grid-cols-[110px_1fr_86px] items-center gap-2 text-sm">
                        <strong className="capitalize">{aspect.aspect}</strong>
                        <div className="h-2 overflow-hidden rounded-full bg-slate-200">
                          <div className={`h-full ${sentimentClass(aspect.label)}`} style={{ width: `${Math.round((aspect.sentiment + 1) * 50)}%` }} />
                        </div>
                        <span className={`text-right text-xs font-bold ${sentimentTextClass(aspect.label)}`}>{aspect.label}</span>
                      </div>
                    ))}
                  </div>
                ) : (
                  <p className="text-sm text-muted">No aspect evidence found.</p>
                )}
              </section>

              <section>
                <h2 className="mb-3 text-lg font-bold">Recurring themes</h2>
                <div className="grid gap-3 xl:grid-cols-3">
                  {selectedDetail.themes.length ? (
                    selectedDetail.themes.map((theme) => (
                      <article key={theme.name} className="rounded-lg border border-line bg-white p-4">
                        <h3 className="text-sm font-bold">{theme.name}</h3>
                        <div className="mt-2 flex flex-wrap gap-2 text-xs text-muted">
                          <span>{theme.reviewCount} reviews</span>
                          <span>Avg quality {format(theme.avgQuality)}</span>
                          <span>{theme.keywords.join(", ")}</span>
                        </div>
                        <p className="mt-3 text-sm leading-6">{theme.representativeReview.comment}</p>
                      </article>
                    ))
                  ) : (
                    <p className="text-sm text-muted">Not enough written evidence to form themes.</p>
                  )}
                </div>
              </section>
            </div>
          )}
        </section>
      </div>

    </main>
  );
}
