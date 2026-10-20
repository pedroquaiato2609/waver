import { ChangeDetectionStrategy, Component, forwardRef, input, signal } from '@angular/core';
import { ControlValueAccessor, NG_VALUE_ACCESSOR } from '@angular/forms';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';

export interface WvOption {
  value: string;
  label: string;
}

let nextId = 0;

/** Single choice from a short list: a ControlValueAccessor over Material's select (RFC-0021 wrapper inventory). */
@Component({
  selector: 'wv-select',
  imports: [MatFormFieldModule, MatSelectModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [{ provide: NG_VALUE_ACCESSOR, useExisting: forwardRef(() => WvSelect), multi: true }],
  template: `
    <mat-form-field appearance="outline" subscriptSizing="dynamic">
      <mat-label>{{ label() }}</mat-label>
      <mat-select [id]="id" [value]="value()" [disabled]="disabled()" (selectionChange)="onSelect($event.value)" (closed)="touched()">
        @for (o of options(); track o.value) {
          <mat-option [value]="o.value">{{ o.label }}</mat-option>
        }
      </mat-select>
      @if (hint()) { <mat-hint>{{ hint() }}</mat-hint> }
    </mat-form-field>
  `,
  styles: `
    :host { display: block; }
    mat-form-field { width: 100%; }
  `,
})
export class WvSelect implements ControlValueAccessor {
  readonly label = input.required<string>();
  readonly options = input.required<WvOption[]>();
  readonly hint = input<string>();
  readonly id = `wv-select-${nextId++}`;

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
  protected onSelect(value: string): void {
    this.value.set(value);
    this.onChange(value);
  }
}
