import { createBrowserRouter } from "react-router";

import { RootLayout } from "../components/RootLayout";
import { RouteErrorPage } from "../components/RouteErrorPage";
import { TripListPage } from "../features/trips/TripListPage";

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
    ],
  },
]);
