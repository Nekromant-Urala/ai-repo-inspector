import { inject, Injectable } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { map, timeout, TimeoutError } from 'rxjs';
import { CheckSubmission, readCheck, readReport } from './check.models';

@Injectable({ providedIn: 'root' })
export class CheckApiService {
  private readonly http = inject(HttpClient);

  create(input: CheckSubmission) {
    if (input.type === 'ARCHIVE') {
      const body = new FormData();
      if (input.file) body.append('file', input.file);
      if (input.customCriteria.length) {
        body.append('customCriteria', new Blob([JSON.stringify(input.customCriteria)], { type: 'application/json' }));
      }
      return this.http.post<unknown>('/api/checks/archive', body).pipe(timeout(120_000), map(readCheck));
    }
    return this.http.post<unknown>('/api/checks', {
      type: 'GIT_URL',
      value: input.url.trim().replace(/\/$/, ''),
      ...(input.customCriteria.length ? { customCriteria: input.customCriteria } : {}),
    }).pipe(timeout(30_000), map(readCheck));
  }

  run(id: number) {
    return this.http.post<unknown>(`/api/checks/${id}/run`, {}).pipe(map(value => {
      const report = readReport(value);
      if (report.checkId !== id) throw new Error('Получен отчёт другой проверки. Начните новую проверку.');
      return report;
    }));
  }

  status(id: number) {
    return this.http.get<unknown>(`/api/checks/${id}`).pipe(timeout(15_000), map(value => {
      const check = readCheck(value);
      if (check.id !== id) throw new Error('Сервис вернул данные другой проверки.');
      return check;
    }));
  }
}

export function checkErrorMessage(error: unknown): string {
  if (error instanceof TimeoutError) return 'Сервис долго не отвечает. Проверьте соединение и попробуйте позже.';
  if (error instanceof HttpErrorResponse) {
    if (error.status === 0 || error.status === 502 || error.status === 503) {
      return 'Не удалось связаться с сервисом проверки. Убедитесь, что он запущен, и попробуйте позже.';
    }
    if (error.status === 404) return 'Проверка не найдена или эта возможность пока недоступна.';
    if (error.status === 413) return 'Архив слишком большой. Выберите файл меньшего размера.';
    if (error.status === 415) return 'Этот формат архива пока не поддерживается.';
    if (error.status === 504) return 'Время ожидания ответа истекло. Анализ мог продолжиться — проверьте его статус.';
    if (error.status >= 500) return 'Сервис не смог обработать запрос. Проверьте доступность репозитория и попробуйте позже.';
    return 'Сервис не принял запрос. Проверьте данные проекта и попробуйте снова.';
  }
  return error instanceof Error ? error.message : 'Произошла ошибка. Попробуйте ещё раз.';
}
