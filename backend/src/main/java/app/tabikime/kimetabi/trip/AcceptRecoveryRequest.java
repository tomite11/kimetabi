package app.tabikime.kimetabi.trip;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AcceptRecoveryRequest(
        @NotBlank @Size(min = 22, max = 500) String token
) {
}
