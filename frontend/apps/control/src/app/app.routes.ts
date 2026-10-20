import { Route } from '@angular/router';

export const appRoutes: Route[] = [
  { path: '', pathMatch: 'full', redirectTo: 'revendas' },
  {
    path: 'revendas',
    title: 'Revendas | WAVER Control',
    loadComponent: () => import('@waver/control-feature-resellers').then((m) => m.ResellersPage),
  },
  {
    path: 'revendas/:id',
    title: 'Revenda | WAVER Control',
    loadComponent: () => import('@waver/control-feature-resellers').then((m) => m.ResellerDetailPage),
  },
  {
    path: 'catalogo',
    title: 'Catálogo | WAVER Control',
    loadComponent: () => import('@waver/control-feature-catalog').then((m) => m.CatalogPage),
  },
  {
    path: 'catalogo/produtos/:id',
    title: 'Produto | WAVER Control',
    loadComponent: () => import('@waver/control-feature-catalog').then((m) => m.ProductDetailPage),
  },
  {
    path: 'catalogo/tabelas/:id',
    title: 'Tabela de preço | WAVER Control',
    loadComponent: () => import('@waver/control-feature-catalog').then((m) => m.PriceBookDetailPage),
  },
  {
    path: 'marketplace',
    title: 'Marketplace | WAVER Control',
    loadComponent: () => import('@waver/control-feature-marketplace').then((m) => m.MarketplacePage),
  },
  {
    path: 'marketplace/:id',
    title: 'Oferta | WAVER Control',
    loadComponent: () => import('@waver/control-feature-marketplace').then((m) => m.ListingDetailPage),
  },
  {
    path: 'organizacoes',
    title: 'Organizações | WAVER Control',
    loadComponent: () => import('@waver/control-feature-organizations').then((m) => m.OrganizationsPage),
  },
  {
    path: 'auditoria',
    title: 'Auditoria | WAVER Control',
    loadComponent: () => import('@waver/control-feature-audit').then((m) => m.AuditPage),
  },
  { path: '**', redirectTo: 'revendas' },
];
