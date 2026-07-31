package app.tabikime.kimetabi.trip;

import java.net.URI;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import app.tabikime.kimetabi.identity.AppPrincipal;

@Validated
@RestController
@RequestMapping("/api/trips")
public class TripController {

    private final TripService tripService;

    public TripController(TripService tripService) {
        this.tripService = tripService;
    }

    @PostMapping
    ResponseEntity<TripSnapshot> createTrip(
            @AuthenticationPrincipal AppPrincipal principal,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @Valid @RequestBody CreateTripRequest request
    ) {
        TripSnapshot snapshot =
                tripService.create(principal.firebaseUid(), idempotencyKey, request);
        return ResponseEntity
                .created(URI.create("/api/trips/" + snapshot.trip().id()))
                .body(snapshot);
    }

    @GetMapping
    TripPage listTrips(
            @AuthenticationPrincipal AppPrincipal principal,
            @RequestParam(required = false) @Size(max = 500) String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit
    ) {
        return tripService.list(principal.firebaseUid(), cursor, limit);
    }

    @GetMapping("/{tripId}")
    TripSnapshot getTripSnapshot(
            @AuthenticationPrincipal AppPrincipal principal,
            @PathVariable @Min(1) long tripId
    ) {
        return tripService.snapshot(principal.firebaseUid(), tripId);
    }

    @PatchMapping("/{tripId}")
    TripResource updateTrip(
            @AuthenticationPrincipal AppPrincipal principal,
            @PathVariable @Min(1) long tripId,
            @Valid @RequestBody UpdateTripRequest request
    ) {
        return tripService.update(principal.firebaseUid(), tripId, request);
    }
}
