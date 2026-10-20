import { ChangeDetectionStrategy, Component, input } from '@angular/core';

export type WvTone = 'success' | 'warning' | 'danger' | 'info' | 'neutral';

/**
 * State badge (custom component, RFC-0021). Commercial, financial and technical states are always separate
 * badges (Commercial §13); the tone carries meaning, the text says which state it is.
 */
@Component({
  selector: 'wv-status-badge',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { '[class]': "'tone-' + tone()" },
  template: `<span class="dot" aria-hidden="true"></span><ng-content />`,
  styles: `
    :host {
      display: inline-flex; align-items: center; gap: 6px;
      height: 22px; padding: 0 8px; border-radius: var(--wv-radius-sm);
      font-size: var(--wv-font-size-xs); font-weight: var(--wv-font-weight-medium); white-space: nowrap;
    }
    .dot { width: 6px; height: 6px; transform: rotate(45deg); background: currentColor; }
    :host(.tone-success) { color: var(--wv-color-status-success); background: var(--wv-color-status-success-bg); }
    :host(.tone-warning) { color: var(--wv-color-status-warning); background: var(--wv-color-status-warning-bg); }
    :host(.tone-danger) { color: var(--wv-color-status-danger); background: var(--wv-color-status-danger-bg); }
    :host(.tone-info) { color: var(--wv-color-status-info); background: var(--wv-color-status-info-bg); }
    :host(.tone-neutral) { color: var(--wv-color-text-secondary); background: var(--wv-color-bg-surface-muted); }
  `,
})
export class WvStatusBadge {
  readonly tone = input<WvTone>('neutral');
}
