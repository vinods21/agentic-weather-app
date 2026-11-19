import { describe, expect, it } from 'vitest';
import { problem } from '../test/fixtures';
import { ApiError } from './client';
import { describeApiError } from './errors';
import type { Problem } from './types';

const GENERIC = 'Something went wrong. Please try again.';

function http(status: number, body?: Partial<Problem> | Problem, requestId?: string) {
  return new ApiError({ kind: 'http', status, problem: body as Problem | undefined, requestId });
}

describe('describeApiError', () => {
  it('AC-19: network failure names the base URL and is retryable', () => {
    const view = describeApiError(new ApiError({ kind: 'network', baseUrl: 'http://localhost:8080' }));
    expect(view.message).toBe("Can't reach the weather service at http://localhost:8080.");
    expect(view.retryable).toBe(true);
  });

  it('AC-13: LOCATION_NOT_FOUND names the city', () => {
    const view = describeApiError(http(404, problem(404, 'LOCATION_NOT_FOUND')), { city: 'Atlantis' });
    expect(view.message).toBe('No weather data found for "Atlantis".');
    expect(view.retryable).toBe(false);
    expect(view.requestId).toBeUndefined();
  });

  it('AC-14: UPSTREAM_UNAVAILABLE is retryable', () => {
    const view = describeApiError(http(503, problem(503, 'UPSTREAM_UNAVAILABLE')));
    expect(view.message).toBe('The weather service is temporarily unavailable. Please try again.');
    expect(view.retryable).toBe(true);
  });

  it('AC-15: VALIDATION_FAILED on a known field goes inline', () => {
    const body = problem(400, 'VALIDATION_FAILED', { errors: [{ field: 'city', message: 'must not be blank' }] });
    const view = describeApiError(http(400, body), { knownFields: ['city'] });
    expect(view.inlineFieldErrors).toEqual({ city: 'must not be blank' });
    expect(view.otherFieldErrors).toEqual([]);
    expect(view.message).toBe('Please check the highlighted values.');
  });

  it('AC-15: VALIDATION_FAILED on fields without a control is listed', () => {
    const body = problem(400, 'VALIDATION_FAILED', {
      errors: [
        { field: 'city', message: 'must not be blank' },
        { field: 'units', message: 'must be metric or imperial' },
        { field: 'X-Client-Id', message: 'is invalid' },
      ],
    });
    const view = describeApiError(http(400, body), { knownFields: ['city'] });
    expect(view.inlineFieldErrors).toEqual({ city: 'must not be blank' });
    expect(view.otherFieldErrors).toEqual([
      { field: 'units', message: 'must be metric or imperial' },
      { field: 'X-Client-Id', message: 'is invalid' },
    ]);
    expect(view.message).toBe('Some values were not accepted:');
  });

  it('AC-24: duplicate and limit messages', () => {
    expect(describeApiError(http(409, problem(409, 'FAVOURITE_ALREADY_EXISTS')), { city: 'London' }).message).toBe(
      'London is already in your favourites.',
    );
    expect(describeApiError(http(409, problem(409, 'FAVOURITES_LIMIT_REACHED'))).message).toBe(
      "You've reached the maximum number of favourites. Remove one to add another.",
    );
  });

  it('AC-25: NOT_FOUND message', () => {
    const view = describeApiError(http(404, problem(404, 'NOT_FOUND')));
    expect(view.message).toBe('This favourite no longer exists.');
    expect(view.code).toBe('NOT_FOUND');
  });

  it.each([
    ['INTERNAL_ERROR', 500],
    ['METHOD_NOT_ALLOWED', 405],
  ])('AC-18: %s gives the generic text with the reference', (code, status) => {
    const view = describeApiError(http(status, problem(status, code, { requestId: 'req-1' }), 'req-1'));
    expect(view.message).toBe(GENERIC);
    expect(view.requestId).toBe('req-1');
    expect(view.retryable).toBe(true);
  });

  it('AC-18: a non-Problem body gives the generic text, reference from the header', () => {
    const view = describeApiError(http(415, undefined, 'hdr-2'));
    expect(view.message).toBe(GENERIC);
    expect(view.requestId).toBe('hdr-2');
  });

  it('AC-18: an unknown code on a 5xx gives the generic text', () => {
    const view = describeApiError(http(502, problem(502, 'SOMETHING_NEW', { detail: 'Upstream exploded' }), 'r'));
    expect(view.message).toBe(GENERIC);
  });

  it('AC-18: an unknown code on a 4xx shows the Problem detail and the reference', () => {
    const view = describeApiError(http(422, problem(422, 'SOMETHING_NEW', { detail: 'Not today.' }), 'req-9'));
    expect(view.message).toBe('Not today.');
    expect(view.requestId).toBe('req-9');
    expect(view.retryable).toBe(false);
  });

  it('AC-18: an unknown code on a 4xx with an empty detail gives the generic text', () => {
    const view = describeApiError(http(422, problem(422, 'SOMETHING_NEW', { detail: ' ' })));
    expect(view.message).toBe(GENERIC);
  });

  it('AC-18: an unsafe request id is not shown', () => {
    const view = describeApiError(http(500, undefined, '<script>alert(1)</script>'));
    expect(view.requestId).toBeUndefined();
  });

  it('anything that is not an ApiError gives the generic text', () => {
    expect(describeApiError(new Error('boom')).message).toBe(GENERIC);
  });
});
