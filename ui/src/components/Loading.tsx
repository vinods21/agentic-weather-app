export function Loading({ text }: { text: string }) {
  return (
    <p role="status" className="loading">
      {text}
    </p>
  );
}
