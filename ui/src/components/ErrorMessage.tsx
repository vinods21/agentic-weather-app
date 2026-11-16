import type { ErrorView } from '../api/errors';

export interface ErrorMessageProps {
  error: ErrorView;
  /** Shown as a Retry button when the error is retryable. */
  onRetry?: () => void;
  retryLabel?: string;
}

/** An announced error: message, unmatched field errors, reference and optional Retry. Text only. */
export function ErrorMessage({ error, onRetry, retryLabel = 'Retry' }: ErrorMessageProps) {
  return (
    <div role="alert" className="error">
      <p>{error.message}</p>
      {error.otherFieldErrors.length > 0 && (
        <ul>
          {error.otherFieldErrors.map((fieldError, index) => (
            <li key={`${fieldError.field}-${index}`}>
              {fieldError.field}: {fieldError.message}
            </li>
          ))}
        </ul>
      )}
      {error.requestId && <p className="reference">Reference: {error.requestId}</p>}
      {onRetry && error.retryable && (
        <button type="button" onClick={onRetry}>
          {retryLabel}
        </button>
      )}
    </div>
  );
}
