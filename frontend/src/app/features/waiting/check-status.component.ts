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
        <h1>Проверяем статус</h1><p class="muted">Получаем данные о проверке.</p>
      } @else if (error()) {
        <h1>Не удалось получить данные</h1><p class="muted">{{ error() }}</p>
      } @else {
        @if (check(); as item) {
        @switch (item.status) {
          @case ('DONE') {
            <h1>Проверка завершена</h1>
            <p class="muted">Отчёты пока не сохраняются. Чтобы увидеть результат, запустите проверку ещё раз.</p>
          }
          @case ('RUNNING') {
            <h1>Проверка ещё идёт</h1>
            <p class="muted">Здесь можно узнать статус проверки. Если страница с отчётом была закрыта или обновлена, восстановить его пока нельзя.</p>
          }
          @case ('PENDING') {
            <h1>Проверка ещё не началась</h1>
            <p class="muted">Запрос принят, но проект ещё не проверялся. Начните новую проверку.</p>
          }
          @case ('FAILED') {
            <h1>Не удалось проверить проект</h1>
            <p class="muted">Проверьте, что репозиторий доступен по ссылке, и попробуйте ещё раз.</p>
          }
        }
        <p class="source-caption">{{ item.sourceValue }}</p>
        }
      }
      <div class="state-actions">
        <a class="button button-primary" routerLink="/">Новая проверка <span aria-hidden="true">→</span></a>
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
