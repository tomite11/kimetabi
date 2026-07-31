package app.tabikime.kimetabi.trip;

import java.net.URI;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
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

    @PostMapping("/{tripId}/owner-transfer")
    TripSnapshot transferOwner(
            @AuthenticationPrincipal AppPrincipal principal,
            @PathVariable @Min(1) long tripId,
            @Valid @RequestBody TransferOwnerRequest request
    ) {
        return tripService.transferOwner(
                principal.firebaseUid(), tripId, request.memberId(), request.version());
    }

    @PostMapping("/{tripId}/leave")
    TripSnapshot leaveTrip(
            @AuthenticationPrincipal AppPrincipal principal,
            @PathVariable @Min(1) long tripId,
            @Valid @RequestBody MemberMutationRequest request
    ) {
        return tripService.leave(principal.firebaseUid(), tripId, request.version());
    }

    @DeleteMapping("/{tripId}/members/{memberId}")
    TripSnapshot removeMember(
            @AuthenticationPrincipal AppPrincipal principal,
            @PathVariable @Min(1) long tripId,
            @PathVariable @Min(1) long memberId,
            @Valid @RequestBody MemberMutationRequest request
    ) {
        return tripService.removeMember(
                principal.firebaseUid(), tripId, memberId, request.version());
    }

    @ExceptionHandler(MemberLifecycleConflictException.class)
    ResponseEntity<ProblemDetail> handleMemberConflict(
            MemberLifecycleConflictException exception,
            HttpServletRequest request
    ) {
        ProblemDetail problem = memberProblem(
                HttpStatus.CONFLICT,
                "VERSION_CONFLICT",
                "旅行が別の操作で更新されています。",
                request);
        problem.setProperty("currentVersion", exception.current().trip().version());
        problem.setProperty("current", exception.current());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    @ExceptionHandler(MemberLifecycleValidationException.class)
    ResponseEntity<ProblemDetail> handleMemberValidation(
            MemberLifecycleValidationException exception,
            HttpServletRequest request
    ) {
        return ResponseEntity.unprocessableEntity().body(memberProblem(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "VALIDATION_FAILED",
                exception.getMessage(),
                request));
    }

    private ProblemDetail memberProblem(
            HttpStatus status,
            String code,
            String message,
            HttpServletRequest request
    ) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, message);
        problem.setType(URI.create("https://tabikime.app/problems/"
                + (status == HttpStatus.CONFLICT ? "conflict" : "validation-failed")));
        problem.setTitle(status == HttpStatus.CONFLICT ? "競合が発生しました" : "入力値が不正です");
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", code);
        problem.setProperty("message", message);
        Object traceId = request.getAttribute(
                "app.tabikime.kimetabi.support.web.TraceIdFilter.traceId");
        problem.setProperty("traceId", traceId instanceof String value ? value : "unavailable");
        return problem;
    }
}
