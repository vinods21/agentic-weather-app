// Open-Meteo attribution (ADR 0005, AC-33). Always shown: the UI cannot tell which provider
// the backend uses.
export function Footer() {
  return (
    <footer className="footer">
      <p>
        Weather data by <a href="https://open-meteo.com/">Open-Meteo.com</a>, licensed under{' '}
        <a href="https://creativecommons.org/licenses/by/4.0/">CC BY 4.0</a>.
      </p>
    </footer>
  );
}
