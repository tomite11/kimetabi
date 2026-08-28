package app.tabikime.kimetabi.realtime;

import java.util.Map;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

@Component
public class TripEventPublisher {

    private final SimpMessagingTemplate messagingTemplate;

    public TripEventPublisher(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    public void publish(long tripId, Map<String, Object> payload) {
        messagingTemplate.convertAndSend("/topic/trip/" + tripId, (Object) payload);
    }
}
