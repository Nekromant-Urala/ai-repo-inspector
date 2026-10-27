import { Component } from '@angular/core';

@Component({
  selector: 'app-root',
  standalone: true,
  template: `
    <main class="shell">
      <h1>AI-Repo Inspector</h1>
      <p>Интеллектуальный аудитор качества Git-репозиториев.</p>
      <!-- TODO: поле ввода ссылки GitHub/GitLab + загрузка архива,
           страница ожидания, вывод отчёта по критериям. -->
    </main>
  `,
  styles: [`
    .shell { font-family: system-ui, sans-serif; max-width: 720px; margin: 48px auto; padding: 0 16px; }
  `],
})
export class AppComponent {}