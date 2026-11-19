import { useEffect, useRef, type ReactNode } from 'react';

/** The view's h1; it takes focus on mount after in-app navigation (AC-32). */
export function ViewHeading({ focusOnMount, children }: { focusOnMount: boolean; children: ReactNode }) {
  const ref = useRef<HTMLHeadingElement>(null);
  useEffect(() => {
    if (focusOnMount) ref.current?.focus();
    // Only on mount: a view remounts on every route change.
  }, []);
  return (
    <h1 ref={ref} tabIndex={-1}>
      {children}
    </h1>
  );
}
