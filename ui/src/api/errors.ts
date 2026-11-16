// Maps a failed API call to what the UI shows (design "Error handling", AC-13..AC-19, AC-24).
import { CLIENT_ID_PATTERN } from '../storage/clientId';
import { ApiError } from './client';
import {
  GENERIC_MESSAGE,
  PROBLEM_MESSAGES,
  VALIDATION_INLINE_MESSAGE,
  VALIDATION_LIST_MESSAGE,
  networkMessage,
} from './messages';
import type { FieldError, ProblemCode } from './types';

export interface ErrorView {
  message: string;
  /** Field name to message, for fields that have a control in the current form. */
  inlineFieldErrors: Record<string, string>;
  /** Field errors without a matching control, listed in the general message. */
  otherFieldErrors: FieldError[];
  /** Shown as "Reference: <id>"; only set when it matches a safe pattern. */
  requestId?: string;
  retryable: boolean;
  /** The Problem code, when the response carried a known one. */
  code?: ProblemCode;
}

export interface ErrorContext {
  /** The submitted city, for the not-found and duplicate texts. */
  city?: string;
  /** Field names that have their own input in the current form. */
  knownFields?: readonly string[];
}

// The same safe pattern as the client id: plain id characters only, 1..64 long.
const SAFE_REQUEST_ID = CLIENT_ID_PATTERN;

function safeRequestId(id: string | undefined): string | undefined {
  return id !== undefined && SAFE_REQUEST_ID.test(id) ? id : undefined;
}

function isKnownCode(code: string): code is ProblemCode {
  return Object.hasOwn(PROBLEM_MESSAGES, code);
}

function fill(text: string, city: string | undefined): string {
  return text.replaceAll('{city}', city ?? '');
}

function generic(requestId: string | undefined): ErrorView {
  return { message: GENERIC_MESSAGE, inlineFieldErrors: {}, otherFieldErrors: [], requestId, retryable: true };
}

export function describeApiError(error: unknown, ctx: ErrorContext = {}): ErrorView {
  if (!(error instanceof ApiError)) return generic(undefined);
  const failure = error.failure;

  // Rule 1: network failure, CORS block or timeout.
  if (failure.kind === 'network') {
    return {
      message: networkMessage(failure.baseUrl),
      inlineFieldErrors: {},
      otherFieldErrors: [],
      retryable: true,
    };
  }

  const requestId = safeRequestId(failure.requestId);
  const problem = failure.problem;

  // Rule 3 (part): a body that is not Problem Details.
  if (!problem) return generic(requestId);

  const code = problem.code;
  if (isKnownCode(code)) {
    // Rule 2: known codes.
    if (code === 'INTERNAL_ERROR' || code === 'METHOD_NOT_ALLOWED') return { ...generic(requestId), code };

    if (code === 'VALIDATION_FAILED') {
      const known = ctx.knownFields ?? [];
      const inline: Record<string, string> = {};
      const others: FieldError[] = [];
      for (const fieldError of Array.isArray(problem.errors) ? problem.errors : []) {
        if (typeof fieldError?.field !== 'string' || typeof fieldError.message !== 'string') continue;
        if (known.includes(fieldError.field)) {
          const existing = inline[fieldError.field];
          inline[fieldError.field] = existing ? `${existing}; ${fieldError.message}` : fieldError.message;
        } else {
          others.push({ field: fieldError.field, message: fieldError.message });
        }
      }
      const allInline = others.length === 0 && Object.keys(inline).length > 0;
      return {
        message: allInline ? VALIDATION_INLINE_MESSAGE : VALIDATION_LIST_MESSAGE,
        inlineFieldErrors: inline,
        otherFieldErrors: others,
        retryable: false,
        code,
      };
    }

    const entry = PROBLEM_MESSAGES[code];
    return {
      message: fill(entry.text, ctx.city),
      inlineFieldErrors: {},
      otherFieldErrors: [],
      retryable: entry.retryable,
      code,
    };
  }

  // Rule 3: unknown code on a 5xx.
  if (failure.status >= 500) return generic(requestId);

  // Rule 4: unknown code on a 4xx shows the Problem detail.
  const detail = typeof problem.detail === 'string' && problem.detail.trim() !== '' ? problem.detail : GENERIC_MESSAGE;
  return { message: detail, inlineFieldErrors: {}, otherFieldErrors: [], requestId, retryable: false };
}
