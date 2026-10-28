import { Routes } from '@angular/router';

export const routes: Routes = [
  { path: '', title: 'Проверить проект — AI-Repo Inspector', loadComponent: () => import('./features/submit/submit.component').then(m => m.SubmitComponent) },
  { path: 'checks/:id/wait', title: 'Анализ проекта — AI-Repo Inspector', loadComponent: () => import('./features/waiting/waiting.component').then(m => m.WaitingComponent) },
  { path: 'checks/:id/report', title: 'Отчёт — AI-Repo Inspector', loadComponent: () => import('./features/report/report.component').then(m => m.ReportComponent) },
  { path: '**', title: 'Страница не найдена — AI-Repo Inspector', loadComponent: () => import('./features/not-found/not-found.component').then(m => m.NotFoundComponent) },
];
