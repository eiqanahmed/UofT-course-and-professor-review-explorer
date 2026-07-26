import type { CourseDetail, Overview, ProfessorDetail, SearchResponse } from "./types";

async function getJson<T>(path: string): Promise<T> {
  const response = await fetch(path);
  if (!response.ok) {
    throw new Error(`Request failed with status ${response.status}`);
  }
  return response.json() as Promise<T>;
}

export function getOverview() {
  return getJson<Overview>("/api/overview");
}

export function searchEntities(params: URLSearchParams) {
  return getJson<SearchResponse>(`/api/search?${params.toString()}`);
}

export function getCourse(courseCode: string) {
  return getJson<CourseDetail>(`/api/courses/${encodeURIComponent(courseCode)}`);
}

export function getProfessor(professorId: number) {
  return getJson<ProfessorDetail>(`/api/professors/${professorId}`);
}
