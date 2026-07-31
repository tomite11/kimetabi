package app.tabikime.kimetabi.trip;

import java.net.URI;
import java.time.Instant;

public record RecoveryLink(long id, URI url, Instant expiresAt) {
}
