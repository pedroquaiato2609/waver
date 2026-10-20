import { ChangeDetectionStrategy, Component, input } from '@angular/core';

/**
 * The official WAVER faceted symbol (BRAND_GUIDELINES §2). Geometry from logos/svg, fitted to the master PNG.
 * `tone` follows the brand rules: on dark backgrounds the outer facets are Off White and the middle facet Sage.
 */
@Component({
  selector: 'wv-symbol',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <svg viewBox="0 0 436 203" aria-hidden="true">
      <polygon class="outer" points="0,7 151,0 215,81 113,203" />
      <polygon class="mid" points="174,63 260,68 345,14 248,203" />
      <polygon class="outer" points="270,85 435,0 338,190" />
    </svg>
  `,
  styles: `
    :host { display: inline-block; line-height: 0; }
    svg { width: 100%; height: auto; display: block; overflow: visible; }
    :host(.on-dark) .outer { fill: var(--wv-off-white, #F7F6F2); }
    :host(.on-dark) .mid { fill: var(--wv-sage-300, #A7C4B0); }
    :host(.on-light) .outer { fill: var(--wv-forest-900, #0F3D2E); }
    :host(.on-light) .mid { fill: var(--wv-mineral-600, #2E7D6B); }
  `,
  host: { role: 'img', '[attr.aria-label]': 'label()', '[class.on-dark]': "tone() === 'dark'", '[class.on-light]': "tone() === 'light'" },
})
export class WaverSymbol {
  readonly tone = input<'dark' | 'light'>('dark');
  readonly label = input('WAVER');
}
