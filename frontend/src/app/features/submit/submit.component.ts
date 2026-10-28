import { Component, ElementRef, inject, ViewChild } from '@angular/core';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { CheckFlowService } from '../../core/check-flow.service';
import { archiveError, CHECK_FEATURES, CustomCriterion, repositoryUrlError, SourceType } from '../../core/check.models';
import { CriterionDialogComponent } from '../custom-criteria/criterion-dialog.component';

@Component({
  selector: 'app-submit',
  standalone: true,
  imports: [ReactiveFormsModule, RouterLink, CriterionDialogComponent],
  templateUrl: './submit.component.html',
})
export class SubmitComponent {
  readonly flow = inject(CheckFlowService);
  readonly features = CHECK_FEATURES;
  readonly url = new FormControl(this.flow.url, {
    nonNullable: true,
    validators: [control => repositoryUrlError(control.value) ? { repositoryUrl: true } : null],
  });
  @ViewChild('fileInput') fileInput?: ElementRef<HTMLInputElement>;
  @ViewChild('addCriterionButton') addCriterionButton?: ElementRef<HTMLButtonElement>;
  submitted = false;
  fileError = '';
  dragging = false;
  dialogOpen = false;
  editingIndex: number | null = null;
  private dialogTrigger: HTMLElement | null = null;
  readonly modules = [
    { mark: 'Aa', name: 'README', description: 'Документация и инструкции' },
    { mark: '</>', name: 'Качество кода', description: 'Настройки линтера и пометки в коде' },
    { mark: '✓', name: 'Тестирование', description: 'Наличие тестов в проекте' },
    { mark: '▤', name: 'Структура', description: 'Организация файлов и папок' },
    { mark: '⑂', name: 'Git-история', description: 'История изменений' },
    { mark: '◇', name: 'Безопасность', description: 'Ключи и токены в коде' },
  ];

  get urlError(): string | null {
    return this.url.touched || this.submitted ? repositoryUrlError(this.url.value) : null;
  }

  get editingCriterion(): CustomCriterion | null {
    return this.editingIndex === null ? null : this.flow.criteria()[this.editingIndex] ?? null;
  }

  setSource(type: SourceType): void {
    if (this.flow.busy()) return;
    this.flow.sourceType = type;
    this.flow.error.set('');
    this.submitted = false;
  }

  rememberUrl(): void { this.flow.url = this.url.value; this.flow.error.set(''); }

  selectFile(event: Event): void {
    const input = event.target as HTMLInputElement;
    this.acceptFiles(input.files);
    input.value = '';
  }

  onDrop(event: DragEvent): void {
    event.preventDefault();
    this.dragging = false;
    if (!this.flow.busy()) this.acceptFiles(event.dataTransfer?.files ?? null);
  }

  acceptFiles(files: FileList | null): void {
    if (!files?.length) return;
    this.flow.error.set('');
    if (files.length !== 1) { this.fileError = 'Выберите один архив проекта.'; return; }
    const file = files[0];
    this.fileError = archiveError(file) ?? '';
    if (!this.fileError) this.flow.file = file;
  }

  removeFile(): void { this.flow.file = null; this.fileError = ''; this.flow.error.set(''); }

  fileSize(bytes: number): string {
    return bytes < 1024 * 1024 ? `${Math.ceil(bytes / 1024)} КиБ` : `${(bytes / 1024 / 1024).toFixed(1)} МиБ`;
  }

  openCriterion(event: Event, index: number | null = null): void {
    this.dialogTrigger = event.currentTarget as HTMLElement;
    this.editingIndex = index;
    this.dialogOpen = true;
  }

  saveCriterion(criterion: CustomCriterion): void {
    this.flow.criteria.update(criteria => this.editingIndex === null
      ? [...criteria, criterion]
      : criteria.map((item, index) => index === this.editingIndex ? criterion : item));
    this.closeDialog();
  }

  closeDialog(): void {
    this.dialogOpen = false;
    this.dialogTrigger?.focus();
  }

  removeCriterion(index: number): void {
    this.flow.criteria.update(criteria => criteria.filter((_, itemIndex) => index !== itemIndex));
    this.flow.error.set('');
    this.addCriterionButton?.nativeElement.focus();
  }

  submit(): void {
    if (this.flow.busy()) return;
    this.submitted = true;
    this.url.markAsTouched();
    this.flow.url = this.url.value;
    if (this.flow.sourceType === 'GIT_URL' && this.url.invalid) return;
    if (this.flow.sourceType === 'ARCHIVE' && (!this.flow.file || this.fileError)) {
      this.fileError ||= 'Выберите архив проекта.';
      return;
    }
    void this.flow.submit({
      type: this.flow.sourceType, url: this.url.value,
      file: this.flow.file, customCriteria: this.flow.criteria(),
    });
  }
}
