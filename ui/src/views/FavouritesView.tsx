import { ViewHeading } from '../components/ViewHeading';

export function FavouritesView({ focusHeading }: { focusHeading: boolean }) {
  return <ViewHeading focusOnMount={focusHeading}>Favourites</ViewHeading>;
}
