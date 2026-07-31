package app.tabikime.kimetabi.trip;

import java.net.URI;
import java.time.Instant;

public record InvitationLink(long id, URI url, Instant expiresAt) {
}
