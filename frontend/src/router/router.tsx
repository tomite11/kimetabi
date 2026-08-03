import { createBrowserRouter } from "react-router";

import { RootLayout } from "../components/RootLayout";
import { RouteErrorPage } from "../components/RouteErrorPage";
import { JoinPage } from "../features/invitations/JoinPage";
import { RecoveryPage } from "../features/invitations/RecoveryPage";
import { ExpensePage, FutureActionPage } from "../features/trips/ExpensePage";
import { PlanPage } from "../features/trips/PlanPage";
import { TripHomePage } from "../features/trips/TripHomePage";
import { TripListPage } from "../features/trips/TripListPage";
import { TripShell } from "../features/trips/TripShell";

export const router = createBrowserRouter([
  {
    path: "/",
    Component: RootLayout,
    ErrorBoundary: RouteErrorPage,
    children: [
      {
        index: true,
        Component: TripListPage,
      },
      {
        path: "join/:inviteToken",
        Component: JoinPage,
      },
      {
        path: "recover/:recoveryToken",
        Component: RecoveryPage,
      },
      {
        path: "t/:tripId",
        Component: TripShell,
        children: [
          { index: true, Component: TripHomePage },
          { path: "plan", Component: PlanPage },
          {
            path: "plan/new",
            element: <FutureActionPage kind="candidate" />,
          },
          { path: "expenses", Component: ExpensePage },
          {
            path: "expenses/new",
            element: <FutureActionPage kind="expense" />,
          },
          {
            path: "settle",
            element: <FutureActionPage kind="settlement" />,
          },
        ],
      },
    ],
  },
]);
