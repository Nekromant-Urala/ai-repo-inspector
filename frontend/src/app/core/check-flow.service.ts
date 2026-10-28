import { computed, inject, Injectable, signal } from '@angular/core';
import { Router } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { CheckApiService, checkErrorMessage } from './check-api.service';
import {
  AnalysisReport, archiveError, CHECK_FEATURES, CheckResponse, CheckSubmission,
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
  sourceType: SourceType = 'GIT_URL';
  url = '';
  file: File | null = null;
  criteria = signal<CustomCriterion[]>([]);

  async submit(input: CheckSubmission): Promise<void> {
    if (this.busy()) return;
    this.error.set('');
    const validation = input.type === 'GIT_URL'
      ? repositoryUrlError(input.url)
      : input.file ? archiveError(input.file) : 'Выберите архив проекта.';
    if (validation) { this.error.set(validation); return; }
    if (input.type === 'ARCHIVE' && !CHECK_FEATURES.archiveUpload) {
      this.error.set('Архивы пока не поддерживаются. Укажите ссылку на репозиторий.');
      return;
    }
    if (input.customCriteria.length && !CHECK_FEATURES.customCriteria) {
      this.error.set('Дополнительные критерии пока не поддерживаются. Удалите их из списка, чтобы проверить проект.');
      return;
    }
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
