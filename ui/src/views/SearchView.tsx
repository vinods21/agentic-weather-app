import { ViewHeading } from '../components/ViewHeading';

export function SearchView({ focusHeading }: { focusHeading: boolean }) {
  return <ViewHeading focusOnMount={focusHeading}>Search</ViewHeading>;
}
