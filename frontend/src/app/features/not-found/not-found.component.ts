import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';

@Component({
  selector: 'app-not-found', standalone: true, imports: [RouterLink],
  template: `<section class="panel state-card"><p class="eyebrow">404 · СТРАНИЦА НЕ НАЙДЕНА</p>
    <h1>Здесь пока ничего нет.</h1><p class="muted">Вернитесь к форме и начните проверку проекта.</p>
    <a class="button button-primary" routerLink="/">На главную →</a></section>`,
})
export class NotFoundComponent {}
