import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';

@Component({
  selector: 'app-not-found', standalone: true, imports: [RouterLink],
  template: `<section class="panel state-card"><p class="eyebrow">ОШИБКА 404</p>
    <h1>Страница не найдена</h1><p class="muted">Проверьте адрес или вернитесь на главную.</p>
    <a class="button button-primary" routerLink="/">На главную →</a></section>`,
})
export class NotFoundComponent {}
