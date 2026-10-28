import { Component, inject, Input, OnChanges, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { CheckApiService, checkErrorMessage } from '../../core/check-api.service';
import { CheckResponse } from '../../core/check.models';

@Component({
  selector: 'app-check-status',
  standalone: true,
  imports: [RouterLink],
  template: `
    <section class="panel state-card" aria-live="polite" [attr.aria-busy]="loading()">
      <span class="state-symbol" aria-hidden="true">{{ loading() ? '⋯' : 'i' }}</span>
      <p class="eyebrow">ПРОВЕРКА #{{ id }}</p>
      @if (loading()) {
        <h1>Уточняем статус</h1><p class="muted">Запрашиваем состояние проверки.</p>
      } @else if (error()) {
        <h1>Не удалось получить данные</h1><p class="muted">{{ error() }}</p>
      } @else {
        @if (check(); as item) {
        @switch (item.status) {
          @case ('DONE') {
            <h1>Анализ завершён</h1>
            <p class="muted">Отчёт из предыдущего сеанса недоступен. Сервис пока не сохраняет результаты для повторного просмотра.</p>
          }
          @case ('RUNNING') {
            <h1>Проект ещё анализируется</h1>
            <p class="muted">Проверка выполняется. После обновления страницы связь с отчётом была потеряна; повторный запуск автоматически не выполняется.</p>
          }
          @case ('PENDING') {
            <h1>Проверка создана</h1>
            <p class="muted">Анализ ещё не запущен. Начните новую проверку, чтобы получить отчёт в этом сеансе.</p>
          }
          @case ('FAILED') {
            <h1>Проверка не завершилась</h1>
            <p class="muted">Не удалось загрузить или проанализировать проект. Проверьте доступность репозитория и попробуйте снова.</p>
          }
        }
        <p class="source-caption">{{ item.sourceValue }}</p>
        }
      }
      <div class="state-actions">
        <a class="button button-primary" routerLink="/">К новой проверке <span aria-hidden="true">→</span></a>
        <button class="button button-secondary" type="button" [disabled]="loading()" (click)="refresh()">Обновить статус</button>
      </div>
    </section>
  `,
})
export class CheckStatusComponent implements OnChanges {
  @Input({ required: true }) id!: number;
  private readonly api = inject(CheckApiService);
  private request = 0;
  readonly check = signal<CheckResponse | null>(null);
  readonly loading = signal(true);
  readonly error = signal('');

  ngOnChanges(): void { void this.refresh(); }

  async refresh(): Promise<void> {
    const request = ++this.request;
    this.loading.set(true);
    this.error.set('');
    try {
      const check = await firstValueFrom(this.api.status(this.id));
      if (request === this.request) this.check.set(check);
    } catch (error) {
      if (request === this.request) this.error.set(checkErrorMessage(error));
    } finally {
      if (request === this.request) this.loading.set(false);
    }
  }
}
