import { ChangeDetectionStrategy, Component, computed, inject, OnInit } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { Store } from '@ngrx/store';
import { SessionActions, sessionFeature, signOut, startSignIn } from '@waver/shared-data-access-app-state';
import { WaverSymbol, WvButton } from '@waver/shared-ui-kit';

@Component({
  selector: 'wv-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, WaverSymbol, WvButton],
  templateUrl: './app.html',
  styleUrl: './app.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class App implements OnInit {
  private readonly store = inject(Store);
  protected readonly session = this.store.selectSignal(sessionFeature.selectSessionState);
  protected readonly isStaff = computed(() => this.session().user?.orgType === 'PLATFORM');
  protected readonly initials = computed(() =>
    (this.session().user?.name ?? '')
      .split(' ')
      .filter(Boolean)
      .slice(0, 2)
      .map((part) => part[0]?.toUpperCase())
      .join(''),
  );

  protected readonly nav = [
    { path: '/revendas', label: 'Revendas' },
    { path: '/catalogo', label: 'Catálogo' },
    { path: '/marketplace', label: 'Marketplace' },
    { path: '/organizacoes', label: 'Organizações' },
    { path: '/auditoria', label: 'Auditoria' },
  ];

  ngOnInit(): void {
    this.store.dispatch(SessionActions.load());
  }

  protected signIn(): void {
    startSignIn();
  }

  protected signOut(): void {
    signOut();
  }
}
