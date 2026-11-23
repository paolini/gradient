import { useEffect, useState } from 'react';
import { graphql } from './api';
import type { Course, CourseTable } from './api';

export const courseHref = (courseId: string) => `#/corsi/${encodeURIComponent(courseId)}`;
export const tableHref = (courseId: string, tableId: string) => `${courseHref(courseId)}/tabelle/${encodeURIComponent(tableId)}`;

export function useList<T>(query: string, field: string, parent: string | null, revision: number) {
  const key = JSON.stringify([query, parent, revision]);
  const [state, setState] = useState<{ key: string; items: T[]; loading: boolean; error: string }>({ key, items: [], loading: true, error: '' });
  useEffect(() => {
    let active = true;
    setState({ key, items: [], loading: true, error: '' });
    const variables = field === 'courseTables' ? { courseId: parent } : field === 'studentRows' ? { tableId: parent } : {};
    graphql<Record<string, T[]>>(query, variables).then(
      data => { if (active) setState({ key, items: data[field], loading: false, error: '' }); },
      error => { if (active) setState({ key, items: [], loading: false, error: String(error.message) }); },
    );
    return () => { active = false; };
  }, [query, field, parent, key]);
  return state.key === key ? state : { items: [], loading: true, error: '' };
}

export function formatDate(value: string) {
  const [year, month, day] = value.split('-');
  return `${day}/${month}/${year}`;
}

export function formatDateTime(value: string) {
  // Il server memorizza l'ora UTC senza fuso.
  return new Date(value.endsWith('Z') ? value : `${value}Z`).toLocaleString('it-IT', { dateStyle: 'short', timeStyle: 'short' });
}

export const courseDetails = (course: Course) => [course.degreeProgram, course.academicYear].filter(Boolean).join(' · ');
export const tableDetails = (table: CourseTable) => [table.date && formatDate(table.date), table.location].filter(Boolean).join(' · ');
export const message = (failure: unknown, fallback: string) => failure instanceof Error ? failure.message : fallback;

export function LoadError({ text, onRetry }: { text: string; onRetry: () => void }) {
  return <div role="alert" className="error">{text} <button type="button" onClick={onRetry}>Riprova</button></div>;
}

export function NotFound({ text }: { text: string }) {
  return <div className="empty-state"><p>{text}</p><a href="#/">Torna ai corsi</a></div>;
}

