import { AfterViewInit, Component, ElementRef, EventEmitter, Input, OnInit, Output, ViewChild } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, ValidatorFn, Validators } from '@angular/forms';
import { CustomCriterion } from '../../core/check.models';

const nonBlank: ValidatorFn = control =>
  typeof control.value === 'string' && control.value.trim() ? null : { required: true };

@Component({
  selector: 'app-criterion-dialog',
  standalone: true,
  imports: [ReactiveFormsModule],
  template: `
    <dialog #dialog class="criterion-dialog" aria-labelledby="criterion-title"
      (cancel)="close()" (click)="backdropClick($event)">
      <form [formGroup]="form" (ngSubmit)="save()" novalidate>
        <div class="dialog-heading">
          <div><p class="eyebrow">ДОПОЛНИТЕЛЬНЫЙ КРИТЕРИЙ</p>
            <h2 id="criterion-title">{{ criterion ? 'Изменить критерий' : 'Новый критерий' }}</h2></div>
          <button class="icon-button" type="button" aria-label="Закрыть окно" (click)="close()">×</button>
        </div>
        <p class="muted">Укажите, что проверить и как выставить оценку.</p>
        <label for="criterion-name">Название</label>
        <input id="criterion-name" autofocus formControlName="name" maxlength="120"
          placeholder="Например, доступность интерфейса" [attr.aria-invalid]="invalid('name')"
          aria-describedby="criterion-name-error">
        <p id="criterion-name-error" class="field-error">{{ invalid('name') ? 'Введите название критерия.' : '' }}</p>
        <label for="criterion-scale">Шкала оценивания</label>
        <input id="criterion-scale" formControlName="scale" maxlength="160"
          placeholder="От 0 до 10: чем выше, тем лучше" [attr.aria-invalid]="invalid('scale')"
          aria-describedby="criterion-scale-error">
        <p id="criterion-scale-error" class="field-error">{{ invalid('scale') ? 'Укажите шкалу оценивания.' : '' }}</p>
        <label for="criterion-description">Что проверить</label>
        <textarea id="criterion-description" formControlName="description" maxlength="2000" rows="4"
          placeholder="Например, подписи полей и управление с клавиатуры"
          [attr.aria-invalid]="invalid('description')" aria-describedby="criterion-description-error"></textarea>
        <p id="criterion-description-error" class="field-error">{{ invalid('description') ? 'Опишите, что нужно проверить.' : '' }}</p>
        <div class="dialog-actions">
          <button type="button" class="button button-secondary" (click)="close()">Отмена</button>
          <button type="submit" class="button button-primary">Сохранить критерий</button>
        </div>
      </form>
    </dialog>
  `,
})
export class CriterionDialogComponent implements OnInit, AfterViewInit {
  @Input() criterion: CustomCriterion | null = null;
  @Output() saved = new EventEmitter<CustomCriterion>();
  @Output() closed = new EventEmitter<void>();
  @ViewChild('dialog', { static: true }) dialog!: ElementRef<HTMLDialogElement>;
  readonly form = new FormGroup({
    name: new FormControl('', { nonNullable: true, validators: [nonBlank, Validators.maxLength(120)] }),
    scale: new FormControl('', { nonNullable: true, validators: [nonBlank, Validators.maxLength(160)] }),
    description: new FormControl('', { nonNullable: true, validators: [nonBlank, Validators.maxLength(2000)] }),
  });

  ngOnInit(): void { if (this.criterion) this.form.setValue(this.criterion); }

  ngAfterViewInit(): void { this.dialog.nativeElement.showModal(); }

  invalid(field: 'name' | 'scale' | 'description'): boolean {
    return this.form.controls[field].invalid && this.form.controls[field].touched;
  }

  save(): void {
    this.form.markAllAsTouched();
    if (this.form.invalid) return;
    const value = this.form.getRawValue();
    this.dialog.nativeElement.close();
    this.saved.emit({ name: value.name.trim(), scale: value.scale.trim(), description: value.description.trim() });
  }

  close(): void {
    this.dialog.nativeElement.close();
    this.closed.emit();
  }

  backdropClick(event: MouseEvent): void {
    if (event.target === this.dialog.nativeElement) {
      const rect = this.dialog.nativeElement.getBoundingClientRect();
      if (event.clientX < rect.left || event.clientX > rect.right || event.clientY < rect.top || event.clientY > rect.bottom) this.close();
    }
  }
}
