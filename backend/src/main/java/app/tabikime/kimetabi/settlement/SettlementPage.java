package app.tabikime.kimetabi.settlement;

import java.util.List;

public record SettlementPage(List<SettlementResource> items, String nextCursor) {
}
