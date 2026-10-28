import { DecimalPipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { toSignal } from '@angular/core/rxjs-interop';
import { CheckFlowService } from '../../core/check-flow.service';
import { Finding, parseCheckId, repositoryUrlError, Severity } from '../../core/check.models';
import { CheckStatusComponent } from '../waiting/check-status.component';

@Component({
  selector: 'app-report',
  standalone: true,
  imports: [DecimalPipe, RouterLink, CheckStatusComponent],
  templateUrl: './report.component.html',
})
export class ReportComponent {
  readonly flow = inject(CheckFlowService);
  private readonly params = toSignal(inject(ActivatedRoute).paramMap);
  readonly id = computed(() => parseCheckId(this.params()?.get('id') ?? null));
  readonly report = computed(() => this.flow.reports()[this.id() ?? 0] ?? null);
  readonly filter = signal<Severity | 'ALL'>('ALL');
  readonly filters: { key: Severity | 'ALL'; label: string }[] = [
    { key: 'ALL', label: 'Все замечания' }, { key: 'ERROR', label: 'Ошибки' },
    { key: 'WARNING', label: 'Предупреждения' }, { key: 'INFO', label: 'Информация' },
  ];
  readonly totals = computed(() => {
    const totals = { ALL: 0, ERROR: 0, WARNING: 0, INFO: 0 };
    for (const criterion of this.report()?.criteria ?? []) {
      for (const finding of criterion.findings) { totals[finding.severity]++; totals.ALL++; }
    }
    return totals;
  });
  readonly sourceLink = computed(() => {
    const source = this.report()?.source;
    return source?.type === 'GIT_URL' && !repositoryUrlError(source.value) ? source.value : null;
  });

  findings(items: Finding[]): Finding[] {
    return this.filter() === 'ALL' ? items : items.filter(item => item.severity === this.filter());
  }

  label(severity: Severity): string {
    return { ERROR: 'Ошибка', WARNING: 'Предупреждение', INFO: 'Информация' }[severity];
  }
}
