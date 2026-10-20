import { HttpErrorResponse } from '@angular/common/http';

/** RFC 9457 problem+json with WAVER's stable `code` (CONVENTIONS, STD-008). The UI maps codes, never server text. */
export interface Problem {
  code: string;
  detail?: string;
  correlationId?: string;
  errors?: { field: string; code: string; message: string }[];
}

/** Normalizes any HTTP failure into a Problem: a code from the service, NETWORK, or INTERNAL_ERROR. */
export function toProblem(e: unknown): Problem {
  if (e instanceof HttpErrorResponse && e.error && typeof e.error === 'object' && 'code' in e.error) {
    return e.error as Problem;
  }
  return { code: e instanceof HttpErrorResponse && e.status === 0 ? 'NETWORK' : 'INTERNAL_ERROR' };
}

/** Text for the codes every screen can meet; screens add their own codes first. */
export function commonProblemMessage(p: Problem, action: string): string {
  switch (p.code) {
    case 'NETWORK':
      return 'Sem conexão com o servidor. Verifique a rede e tente de novo.';
    case 'DEPENDENCY_UNAVAILABLE':
      return `Um serviço necessário está fora do ar e não foi possível ${action}. Tente de novo em instantes.`;
    case 'FORBIDDEN':
      return `Seu papel não permite ${action}.`;
    default:
      return `Não foi possível ${action}.${p.correlationId ? ' Código para o suporte: ' + p.correlationId : ''}`;
  }
}
