package app.tabikime.kimetabi.trip;

import java.util.List;

public record TripPage(
        List<TripResource> items,
        String nextCursor
) {
    public TripPage {
        items = List.copyOf(items);
    }
}
