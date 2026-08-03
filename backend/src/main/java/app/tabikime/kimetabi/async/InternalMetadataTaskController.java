package app.tabikime.kimetabi.async;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/internal/tasks/candidates/{candidateId}/metadata")
public class InternalMetadataTaskController {

    private final MetadataTaskProcessor processor;

    InternalMetadataTaskController(MetadataTaskProcessor processor) {
        this.processor = processor;
    }

    @PostMapping
    ResponseEntity<Void> process(
            @PathVariable @Min(1) long candidateId,
            @Valid @RequestBody MetadataTaskRequest request
    ) {
        if (candidateId != request.candidateId()) {
            return ResponseEntity.badRequest().build();
        }
        return switch (processor.process(request)) {
            case COMPLETED, DUPLICATE, PERMANENT_FAILURE ->
                    ResponseEntity.noContent().build();
            case RETRYABLE_FAILURE ->
                    ResponseEntity.internalServerError().build();
        };
    }
}
