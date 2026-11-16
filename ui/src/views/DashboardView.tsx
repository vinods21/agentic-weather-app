import { ViewHeading } from '../components/ViewHeading';

export function DashboardView({ focusHeading }: { focusHeading: boolean }) {
  return <ViewHeading focusOnMount={focusHeading}>Dashboard</ViewHeading>;
}
