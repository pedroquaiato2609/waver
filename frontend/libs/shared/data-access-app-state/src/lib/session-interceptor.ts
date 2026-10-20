import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { catchError, throwError } from 'rxjs';
import { startSignIn } from './session';

/**
 * A 401 from the BFF means the server-side session or its tokens are gone: start the sign-in again
 * instead of showing a broken screen. /api/session itself is public and never triggers this.
 */
export const sessionExpiredInterceptor: HttpInterceptorFn = (req, next) =>
  next(req).pipe(
    catchError((error: unknown) => {
      if (error instanceof HttpErrorResponse && error.status === 401 && req.url.startsWith('/api/') && req.url !== '/api/session') {
        startSignIn();
      }
      return throwError(() => error);
    }),
  );
