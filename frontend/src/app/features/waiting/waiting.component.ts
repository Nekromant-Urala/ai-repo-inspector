import { Component, computed, effect, inject } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { toSignal } from '@angular/core/rxjs-interop';
import { CheckFlowService } from '../../core/check-flow.service';
import { parseCheckId } from '../../core/check.models';
import { CheckStatusComponent } from './check-status.component';

@Component({
  selector: 'app-waiting',
  standalone: true,
  imports: [RouterLink, CheckStatusComponent],
  template: `
    @if (id(); as checkId) {
      @if (flow.check()?.id === checkId && flow.busy()) {
        <section class="panel state-card waiting-card" aria-live="polite" aria-busy="true">
          <div class="orbit-loader" aria-hidden="true"><span>AI</span></div>
          <p class="eyebrow">ПРОВЕРКА #{{ checkId }}</p>
          <h1>Знакомимся с вашим проектом.</h1>
          <p class="muted">Проверяем документацию, код и структуру.<br>Как только анализ завершится, здесь появится отчёт.</p>
          <p class="source-caption">{{ flow.check()?.sourceValue }}</p>
          <div class="waiting-track"><span class="completed">✓ Проект принят</span><span class="current"><span class="small-dot"></span> Анализируем</span><span>Отчёт</span></div>
          <p class="field-help">Оставьте страницу открытой, чтобы получить результат.</p>
        </section>
      } @else if (flow.check()?.id === checkId && flow.error()) {
        <section class="panel state-card" aria-live="polite">
          <span class="state-symbol error-symbol" aria-hidden="true">!</span>
          <p class="eyebrow">ПРОВЕРКА #{{ checkId }}</p>
          <h1>{{ flow.phase() === 'unknown' ? 'Ответ пока не получен' : 'Проверка не завершилась' }}</h1>
          <p class="muted" role="alert">{{ flow.error() }}</p>
          <div class="state-actions"><a routerLink="/" class="button button-primary">Вернуться к проекту →</a>
            <button type="button" class="button button-secondary" (click)="showStatus = !showStatus">Уточнить статус</button></div>
        </section>
        @if (showStatus) { <app-check-status [id]="checkId" /> }
      } @else { <app-check-status [id]="checkId" /> }
    } @else {
      <section class="panel state-card"><h1>Некорректный номер проверки</h1><p class="muted">Проверьте адрес страницы или начните новую проверку.</p>
        <a routerLink="/" class="button button-primary">К новой проверке →</a></section>
    }
  `,
})
export class WaitingComponent {
  readonly flow = inject(CheckFlowService);
  private readonly router = inject(Router);
  private readonly params = toSignal(inject(ActivatedRoute).paramMap);
  readonly id = computed(() => parseCheckId(this.params()?.get('id') ?? null));
  showStatus = false;

  constructor() {
    effect(() => {
      const id = this.id();
      if (id && this.flow.reports()[id]) void this.router.navigate(['/checks', id, 'report'], { replaceUrl: true });
    });
  }
}
