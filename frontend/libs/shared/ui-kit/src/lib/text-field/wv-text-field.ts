import { ChangeDetectionStrategy, Component, forwardRef, input, signal } from '@angular/core';
import { ControlValueAccessor, NG_VALUE_ACCESSOR } from '@angular/forms';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';

let nextId = 0;

/** Text field: a full ControlValueAccessor over Material's form field (RFC-0021 wrapper inventory). */
@Component({
  selector: 'wv-text-field',
  imports: [MatFormFieldModule, MatInputModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [{ provide: NG_VALUE_ACCESSOR, useExisting: forwardRef(() => WvTextField), multi: true }],
  template: `
    <mat-form-field appearance="outline" subscriptSizing="dynamic">
      <mat-label>{{ label() }}</mat-label>
      <input matInput [id]="id" [value]="value()" [disabled]="disabled()" [attr.maxlength]="maxLength()"
             [attr.autocomplete]="autocomplete()" (input)="onInput($event)" (blur)="touched()" />
      @if (hint() && !error()) { <mat-hint>{{ hint() }}</mat-hint> }
      @if (error()) { <mat-hint class="error" role="alert">{{ error() }}</mat-hint> }
    </mat-form-field>
  `,
  styles: `
    :host { display: block; }
    mat-form-field { width: 100%; }
    .error { color: var(--wv-color-status-danger); }
  `,
})
export class WvTextField implements ControlValueAccessor {
  readonly label = input.required<string>();
  readonly hint = input<string>();
  readonly error = input<string | null>(null);
  readonly maxLength = input<number>();
  readonly autocomplete = input('off');
  readonly id = `wv-text-field-${nextId++}`;

  protected readonly value = signal('');
  protected readonly disabled = signal(false);
  private onChange: (value: string) => void = () => undefined;
  protected touched: () => void = () => undefined;

  writeValue(value: string | null): void {
    this.value.set(value ?? '');
  }
  registerOnChange(fn: (value: string) => void): void {
    this.onChange = fn;
  }
  registerOnTouched(fn: () => void): void {
    this.touched = fn;
  }
  setDisabledState(disabled: boolean): void {
    this.disabled.set(disabled);
  }
  protected onInput(event: Event): void {
    const value = (event.target as HTMLInputElement).value;
    this.value.set(value);
    this.onChange(value);
  }
}
