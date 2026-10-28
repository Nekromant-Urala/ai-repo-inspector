import { Component, inject } from '@angular/core';
import { Router, RouterLink, RouterOutlet } from '@angular/router';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [RouterLink, RouterOutlet],
  template: `
    <a class="skip-link" href="#main-content">К содержимому</a>
    <div class="app-shell">
      <header class="site-header"><a routerLink="/" class="brand" aria-label="AI-Repo Inspector — главная">
        <span class="brand-mark" aria-hidden="true">↗</span><span>repo<span class="brand-light">inspector</span><sup>AI</sup></span></a>
        <span class="header-caption"><span class="small-dot"></span> Проверка репозиториев</span></header>
      <nav class="steps" aria-label="Этапы проверки">
        <a routerLink="/" [class.active]="router.url === '/'"><span>01</span> Проект</a><span class="step-line"></span>
        <span [class.active]="router.url.endsWith('/wait')"><span>02</span> Проверка</span><span class="step-line"></span>
        <span [class.active]="router.url.endsWith('/report')"><span>03</span> Отчёт</span>
      </nav>
      <main id="main-content"><router-outlet /></main>
      <footer class="site-footer"><span>AI-Repo Inspector</span><span>Оценка кода, документации и структуры</span></footer>
    </div>
  `,
})
export class AppComponent { readonly router = inject(Router); }
