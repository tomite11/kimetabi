import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useCallback, useEffect } from "react";

import { useAuth } from "../../auth/AuthProvider";
import { expenseKeys, type ExpensePage } from "./expenseApi";
import { flushExpenseQueue, listQueuedExpenses } from "./expenseQueue";

export function useExpenseQueue(tripId: number) {
  const { uid } = useAuth();
  const queryClient = useQueryClient();
  const queueQuery = useQuery({
    queryKey: ["expense-queue", uid, tripId],
    queryFn: () => listQueuedExpenses(uid, tripId),
  });
  const refetchQueue = queueQuery.refetch;
  const flush = useCallback(async () => {
    await flushExpenseQueue(uid, tripId, (expense) => {
      queryClient.setQueryData<ExpensePage>(
        expenseKeys.all(tripId),
        (current) =>
          current
            ? {
                ...current,
                items: [
                  expense,
                  ...current.items.filter((item) => item.id !== expense.id),
                ],
              }
            : { items: [expense], nextCursor: null },
      );
    });
    await refetchQueue();
  }, [queryClient, refetchQueue, tripId, uid]);

  useEffect(() => {
    const retry = () => void flush();
    void flush();
    window.addEventListener("online", retry);
    window.addEventListener("focus", retry);
    return () => {
      window.removeEventListener("online", retry);
      window.removeEventListener("focus", retry);
    };
  }, [flush]);

  return { ...queueQuery, flush };
}
