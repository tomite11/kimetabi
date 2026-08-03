package app.tabikime.kimetabi.async;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/outbox")
public class InternalOutboxController {

    private final OutboxDispatcher dispatcher;

    InternalOutboxController(OutboxDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @PostMapping("/dispatch")
    ResponseEntity<OutboxDispatcher.DispatchResult> dispatch(
            @Valid @RequestBody OutboxDispatchRequest request
    ) {
        var result = dispatcher.dispatch(request.resolvedLimit());
        return result.failed() == 0
                ? ResponseEntity.ok(result)
                : ResponseEntity.internalServerError().body(result);
    }
}
