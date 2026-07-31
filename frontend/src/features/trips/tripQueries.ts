import { queryOptions } from "@tanstack/react-query";

import { apiClient } from "../../api/client";

export const tripKeys = {
  all: ["trips"] as const,
  list: () => [...tripKeys.all, "list"] as const,
};

export const tripListQuery = queryOptions({
  queryKey: tripKeys.list(),
  queryFn: async () => {
    const { data, error, response } = await apiClient.GET("/api/trips");

    if (error || !data) {
      throw new Error(
        `旅行一覧を取得できませんでした（${response?.status || "network"}）`,
      );
    }

    return data;
  },
});
