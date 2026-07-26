export type Confidence = {
  label: "High" | "Medium" | "Low";
  score: number;
  reason: string;
};

export type Summary = {
  reviewCount: number;
  avgQuality: number;
  avgDifficulty: number;
  bayesianQuality: number;
  bayesianPrior: number;
  bayesianPriorLabel: string;
  bayesianPriorReviewCount: number;
  bayesianPriorWeight: number;
  experienceScore: number;
  takeAgainPct: number | null;
  attendanceRequiredPct: number | null;
  mostCommonGrade: string;
  disagreement: number;
  confidence: Confidence;
};

export type CourseSummary = Summary & {
  courseCode: string;
  professorCount: number;
};

export type ProfessorSummary = Summary & {
  professorId: number;
  professorName: string;
  department: string;
  courseCount: number;
  profileUrl?: string;
};

export type Review = {
  id: number;
  courseCode: string;
  professorId: number;
  professorName: string;
  date: string;
  quality: number | null;
  difficulty: number | null;
  grade: string;
  attendance: string;
  wouldTakeAgain: string;
  comment: string;
  thumbsUp: number;
  thumbsDown: number;
  matchScore?: number;
};

export type Aspect = {
  aspect: string;
  mentions: number;
  sentiment: number;
  label: string;
};

export type Theme = {
  name: string;
  keywords: string[];
  reviewCount: number;
  avgQuality: number;
  representativeReview: Review;
};

export type RepresentativeReviews = {
  positive: Review | null;
  neutral: Review | null;
  negative: Review | null;
};

export type CourseDetail = CourseSummary & {
  professors: ProfessorSummary[];
  representativeReviews: RepresentativeReviews;
  aspects: Aspect[];
  themes: Theme[];
};

export type ProfessorDetail = ProfessorSummary & {
  courses: CourseSummary[];
  representativeReviews: RepresentativeReviews;
  aspects: Aspect[];
  themes: Theme[];
};

export type Overview = {
  totals: {
    reviews: number;
    courses: number;
    professors: number;
  };
  topCourses: CourseSummary[];
  topProfessors: ProfessorSummary[];
  grades: string[];
};

export type CombinationPrediction = {
  courseCode: string;
  professorId: number;
  professorName: string;
  department: string;
  experienceScore: number;
  courseReviewCount: number;
  professorReviewCount: number;
  reason: string;
};

export type SearchResponse = {
  courses: CourseSummary[];
  professors: ProfessorSummary[];
  reviews: Review[];
  combinationPrediction?: CombinationPrediction;
};
