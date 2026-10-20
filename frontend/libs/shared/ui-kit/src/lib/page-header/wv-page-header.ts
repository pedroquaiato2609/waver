import { ChangeDetectionStrategy, Component, input } from '@angular/core';

/** Page title, one plain sentence of context, and the page's primary actions on the right. */
@Component({
  selector: 'wv-page-header',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="text">
      <h1>{{ title() }}</h1>
      @if (description()) { <p>{{ description() }}</p> }
    </div>
    <div class="actions"><ng-content /></div>
  `,
  styles: `
    :host { display: flex; align-items: flex-end; justify-content: space-between; gap: var(--wv-space-6); flex-wrap: wrap; }
    h1 { font-size: var(--wv-font-size-2xl); line-height: var(--wv-line-height-tight); }
    p { margin: var(--wv-space-1) 0 0; color: var(--wv-color-text-secondary); max-width: 64ch; }
    .actions { display: flex; gap: var(--wv-space-2); }
  `,
})
export class WvPageHeader {
  readonly title = input.required<string>();
  readonly description = input<string>();
}
