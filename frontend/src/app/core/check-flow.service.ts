import { computed, inject, Injectable, signal } from '@angular/core';
import { Router } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { CheckApiService, checkErrorMessage } from './check-api.service';
import {
  AnalysisReport, archiveError, CheckResponse, CheckSubmission,
  CustomCriterion, repositoryUrlError, SourceType,
} from './check.models';

export interface BatchRow {
  id: number;
  url: string;
  status: 'В очереди' | 'Проверяется' | 'Готово' | 'Ошибка';
  score?: number;
  error?: string;
}

@Injectable({ providedIn: 'root' })
export class CheckFlowService {
  private readonly api = inject(CheckApiService);
  private readonly router = inject(Router);
  readonly phase = signal<'editing' | 'submitting' | 'running' | 'done' | 'failed' | 'unknown'>('editing');
  readonly busy = computed(() => ['submitting', 'running'].includes(this.phase()));
  readonly check = signal<CheckResponse | null>(null);
  readonly reports = signal<Record<number, AnalysisReport>>({});
  readonly error = signal('');

  // Preserve the form across navigation without storing project data in the browser.
  sourceType: SourceType | 'LIST' = 'GIT_URL';
  url = '';
  file: File | null = null;
  criteria = signal<CustomCriterion[]>([]);

  readonly batchRows = signal<BatchRow[]>([]);
  readonly batchBusy = signal(false);

  // НОВЫЙ МЕТОД: отправляет файл (ZIP/TXT) на бэкенд и запускает опрос статусов
  async submitBatch(file: File): Promise<void> {
    if (this.batchBusy()) return;
    this.batchBusy.set(true);
    this.batchRows.set([]);
    this.error.set('');

    try {
      // 1. Отправляем файл на бэкенд и получаем массив созданных задач
      const checks = await firstValueFrom(this.api.createBatch(file, this.criteria()));
      
      // 2. Заполняем таблицу начальными данными (PENDING)
      this.batchRows.set(checks.map(c => ({
        id: c.id,
        url: c.sourceValue,
        status: 'В очереди'
      })));

      // 3. Запускаем опрос статусов для каждого id
      checks.forEach(check => this.pollStatus(check.id));

    } catch (error) {
      this.error.set(checkErrorMessage(error));
    } finally {
      this.batchBusy.set(false);
    }
  }

  // Вспомогательный метод для опроса статуса конкретной задачи
  private pollStatus(id: number): void {
    const updateRow = (patch: Partial<BatchRow>) => 
      this.batchRows.update(rows => rows.map(r => r.id === id ? { ...r, ...patch } : r));

    const interval = setInterval(async () => {
      try {
        const check = await firstValueFrom(this.api.status(id));
        
        if (check.status === 'RUNNING') {
          updateRow({ status: 'Проверяется' });
        } else if (check.status === 'DONE') {
          clearInterval(interval);
          updateRow({ status: 'Готово' });
          // Запрашиваем отчет, чтобы взять итоговый балл
          const report = await firstValueFrom(this.api.report(id));
          updateRow({ score: report.overallScore });
        } else if (check.status === 'FAILED') {
          clearInterval(interval);
          updateRow({ status: 'Ошибка', error: 'Анализ не удался' });
        }
      } catch (e) {
        clearInterval(interval);
        updateRow({ status: 'Ошибка', error: checkErrorMessage(e) });
      }
    }, 2000); // Опрос каждые 2 секунды
  }

  async submit(input: CheckSubmission): Promise<void> {
    if (this.busy()) return;
    this.error.set('');
    const validation = input.type === 'GIT_URL'
      ? repositoryUrlError(input.url)
      : input.file ? archiveError(input.file) : 'Выберите архив проекта.';
    if (validation) { this.error.set(validation); return; }
    this.phase.set('submitting');
    this.check.set(null);
    try {
      const check = await firstValueFrom(this.api.create(input));
      this.check.set(check);
      if (check.status !== 'PENDING') throw new Error('Не удалось запустить проверку. Попробуйте ещё раз.');
      this.phase.set('running');
      await this.router.navigate(['/checks', check.id, 'wait']);
      const report = await firstValueFrom(this.api.run(check.id));
      this.reports.update(reports => ({ ...reports, [report.checkId]: report }));
      this.check.set({ ...check, status: 'DONE' });
      this.phase.set('done');
    } catch (error) {
      const uncertain = !!this.check() && error instanceof HttpErrorResponse
        && [0, 502, 503, 504].includes(error.status);
      this.phase.set(uncertain ? 'unknown' : 'failed');
      this.error.set(checkErrorMessage(error));
      if (this.check() && !uncertain) this.check.update(check => check && { ...check, status: 'FAILED' });
    }
  }
}
