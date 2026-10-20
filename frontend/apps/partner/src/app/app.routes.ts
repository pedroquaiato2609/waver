import { Route } from '@angular/router';

export const appRoutes: Route[] = [
  { path: '', pathMatch: 'full', redirectTo: 'clientes' },
  {
    path: 'clientes',
    title: 'Clientes | WAVER Partner',
    loadComponent: () => import('@waver/partner-feature-customers').then((m) => m.CustomersPage),
  },
  {
    path: 'ofertas',
    title: 'Ofertas disponíveis | WAVER Partner',
    loadComponent: () => import('@waver/partner-feature-offers').then((m) => m.OffersPage),
  },
  {
    path: 'minha-revenda',
    title: 'Minha revenda | WAVER Partner',
    loadComponent: () => import('@waver/partner-feature-account').then((m) => m.AccountPage),
  },
  { path: '**', redirectTo: 'clientes' },
];
