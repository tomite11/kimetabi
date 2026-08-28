package app.tabikime.kimetabi.sync;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

final class RevisionDeliveryFixture {

    private RevisionDeliveryFixture() {
    }

    static List<DeliveredEvent> duplicate(List<DeliveredEvent> committed, int index) {
        List<DeliveredEvent> delivery = new ArrayList<>(committed);
        delivery.add(index + 1, committed.get(index));
        return List.copyOf(delivery);
    }

    static List<DeliveredEvent> omit(List<DeliveredEvent> committed, int index) {
        List<DeliveredEvent> delivery = new ArrayList<>(committed);
        delivery.remove(index);
        return List.copyOf(delivery);
    }

    static List<DeliveredEvent> reverse(List<DeliveredEvent> committed) {
        List<DeliveredEvent> delivery = new ArrayList<>(committed);
        Collections.reverse(delivery);
        return List.copyOf(delivery);
    }

    record DeliveredEvent(UUID eventId, long tripRevision) {
    }
}
