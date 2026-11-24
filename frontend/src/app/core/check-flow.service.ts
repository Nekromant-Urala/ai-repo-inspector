import { computed, inject, Injectable, signal } from '@angular/core';
import { Router } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { CheckApiService, checkErrorMessage } from './check-api.service';
import {
  AnalysisReport, archiveError, CheckResponse, CheckSubmission,
  CustomCriterion, repositoryUrlError, SourceType,
} from './check.models';

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

  async runBatch(urls: string[]): Promise<void> {
    if (this.batchBusy() || !urls.length) return;
    this.batchBusy.set(true);
    const customCriteria = [...this.criteria()];
    this.batchRows.set(urls.map(url => ({url, status: 'В очереди'})));
    const update = (url: string, patch: Partial<BatchRow>) =>
      this.batchRows.update(rows => rows.map(row => row.url === url ? {...row, ...patch} : row));
    const queue = urls.values();
    const worker = async () => {
      for (const url of queue) {
        try {
          update(url, {status: 'Проверяется'});
          const check = await firstValueFrom(this.api.create({type: 'GIT_URL', url, file: null, customCriteria}));
          update(url, {id: check.id});
          const report = await firstValueFrom(this.api.run(check.id));
          update(url, {status: 'Готово', score: report.overallScore});
        } catch (error) {
          const uncertain = error instanceof HttpErrorResponse && [0,502,503,504].includes(error.status);
          update(url, {status: uncertain ? 'Нет ответа' : 'Ошибка', error: checkErrorMessage(error)});
        }
      }
    };
    try { await Promise.all(Array.from({length: Math.min(3, urls.length)}, worker)); }
    finally { this.batchBusy.set(false); }
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
      // The waiting page opens the report. Leaving it does not redirect the user back.
    } catch (error) {
      const uncertain = !!this.check() && error instanceof HttpErrorResponse
        && [0, 502, 503, 504].includes(error.status);
      this.phase.set(uncertain ? 'unknown' : 'failed');
      this.error.set(checkErrorMessage(error));
      if (this.check() && !uncertain) this.check.update(check => check && { ...check, status: 'FAILED' });
    }
  }
}

interface BatchRow { url: string; status: string; id?: number; score?: number; error?: string }
