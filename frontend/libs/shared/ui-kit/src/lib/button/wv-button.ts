import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { MatButton } from '@angular/material/button';

/** WAVER button: Material underneath, brand shape and weight on top (ADR-0027: features never import Material). */
@Component({
  selector: 'wv-button',
  imports: [MatButton],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <button [matButton]="appearance()" [type]="type()" [disabled]="disabled()" (click)="pressed.emit($event)">
      <ng-content />
    </button>
  `,
  styles: `
    :host { display: inline-flex; }
    button {
      --mat-button-filled-container-height: var(--wv-control-height);
      --mat-button-outlined-container-height: var(--wv-control-height);
      --mat-button-text-container-height: var(--wv-control-height);
      --mat-button-filled-label-text-weight: 600;
      --mat-button-outlined-label-text-weight: 600;
      --mat-button-text-label-text-weight: 600;
      --mat-button-filled-label-text-size: var(--wv-font-size-sm);
      --mat-button-outlined-label-text-size: var(--wv-font-size-sm);
      --mat-button-text-label-text-size: var(--wv-font-size-sm);
      --mat-button-filled-horizontal-padding: 14px;
      --mat-button-outlined-outline-color: var(--wv-color-border-control);
      letter-spacing: 0;
    }
  `,
})
export class WvButton {
  readonly variant = input<'primary' | 'secondary' | 'quiet'>('primary');
  readonly type = input<'button' | 'submit'>('button');
  readonly disabled = input(false);
  readonly pressed = output<MouseEvent>();

  protected readonly appearance = computed(() =>
    this.variant() === 'primary' ? 'filled' : this.variant() === 'secondary' ? 'outlined' : 'text',
  );
}
