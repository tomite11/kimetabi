package app.tabikime.kimetabi.support.web;

import java.net.URI;
import java.util.Comparator;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import app.tabikime.kimetabi.trip.IdempotencyConflictException;
import app.tabikime.kimetabi.trip.InvalidCursorException;
import app.tabikime.kimetabi.trip.InvalidAccessTokenException;
import app.tabikime.kimetabi.trip.RecoveryConflictException;
import app.tabikime.kimetabi.trip.TokenRateLimitExceededException;
import app.tabikime.kimetabi.trip.TripNotFoundException;
import app.tabikime.kimetabi.trip.TripForbiddenException;
import app.tabikime.kimetabi.trip.TripValidationException;
import app.tabikime.kimetabi.trip.TripVersionConflictException;
import app.tabikime.kimetabi.candidate.CandidateVersionConflictException;
import app.tabikime.kimetabi.candidate.SlotVersionConflictException;
import app.tabikime.kimetabi.candidate.VoteVersionConflictException;
import app.tabikime.kimetabi.expense.ExpenseVersionConflictException;
import app.tabikime.kimetabi.expense.ExpenseStateConflictException;

@RestControllerAdvice
public class GlobalApiExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalApiExceptionHandler.class);
    private static final URI VALIDATION_TYPE =
            URI.create("https://tabikime.app/problems/validation-failed");
    private static final URI INVALID_REQUEST_TYPE =
            URI.create("https://tabikime.app/problems/invalid-request");
    private static final URI INTERNAL_ERROR_TYPE =
            URI.create("https://tabikime.app/problems/internal-error");
    private static final URI NOT_FOUND_TYPE =
            URI.create("https://tabikime.app/problems/not-found");
    private static final URI CONFLICT_TYPE =
            URI.create("https://tabikime.app/problems/conflict");
    private static final URI FORBIDDEN_TYPE =
            URI.create("https://tabikime.app/problems/forbidden");
    private static final URI RATE_LIMITED_TYPE =
            URI.create("https://tabikime.app/problems/rate-limited");

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> handleValidation(
            MethodArgumentNotValidException exception,
            HttpServletRequest request
    ) {
        List<FieldErrorResponse> fieldErrors = toFieldErrors(exception.getBindingResult());
        ProblemDetail problem = createProblem(
                HttpStatus.BAD_REQUEST,
                VALIDATION_TYPE,
                "入力値が不正です",
                ApiErrorCode.VALIDATION_FAILED,
                "入力内容を確認してください。",
                request
        );
        problem.setProperty("fieldErrors", fieldErrors);
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<ProblemDetail> handleMethodValidation(
            HandlerMethodValidationException exception,
            HttpServletRequest request
    ) {
        List<FieldErrorResponse> fieldErrors = exception.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> new FieldErrorResponse(
                                parameterName(result.getMethodParameter().getParameterName(),
                                        result.getMethodParameter().getParameterIndex()),
                                errorMessage(error.getDefaultMessage())
                        )))
                .sorted(Comparator.comparing(FieldErrorResponse::field))
                .limit(100)
                .toList();
        ProblemDetail problem = createProblem(
                HttpStatus.BAD_REQUEST,
                VALIDATION_TYPE,
                "入力値が不正です",
                ApiErrorCode.VALIDATION_FAILED,
                "入力内容を確認してください。",
                request
        );
        problem.setProperty("fieldErrors", fieldErrors);
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> handleUnreadableRequest(
            HttpMessageNotReadableException exception,
            HttpServletRequest request
    ) {
        ProblemDetail problem = createProblem(
                HttpStatus.BAD_REQUEST,
                INVALID_REQUEST_TYPE,
                "リクエストを読み取れません",
                ApiErrorCode.INVALID_REQUEST,
                "リクエストの形式を確認してください。",
                request
        );
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ProblemDetail> handleTypeMismatch(
            MethodArgumentTypeMismatchException exception,
            HttpServletRequest request
    ) {
        ProblemDetail problem = createProblem(
                HttpStatus.BAD_REQUEST,
                INVALID_REQUEST_TYPE,
                "リクエストを読み取れません",
                ApiErrorCode.INVALID_REQUEST,
                "リクエストの形式を確認してください。",
                request
        );
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(InvalidCursorException.class)
    ResponseEntity<ProblemDetail> handleInvalidCursor(
            InvalidCursorException exception,
            HttpServletRequest request
    ) {
        ProblemDetail problem = createProblem(
                HttpStatus.BAD_REQUEST,
                INVALID_REQUEST_TYPE,
                "リクエストを読み取れません",
                ApiErrorCode.INVALID_REQUEST,
                "カーソルが不正です。",
                request
        );
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(TripValidationException.class)
    ResponseEntity<ProblemDetail> handleTripValidation(
            TripValidationException exception,
            HttpServletRequest request
    ) {
        ProblemDetail problem = createProblem(
                HttpStatus.UNPROCESSABLE_ENTITY,
                VALIDATION_TYPE,
                "入力値が不正です",
                ApiErrorCode.VALIDATION_FAILED,
                "入力内容を確認してください。",
                request
        );
        problem.setProperty(
                "fieldErrors",
                List.of(new FieldErrorResponse(exception.field(), exception.getMessage())));
        return ResponseEntity.unprocessableEntity().body(problem);
    }

    @ExceptionHandler(TripNotFoundException.class)
    ResponseEntity<ProblemDetail> handleTripNotFound(
            TripNotFoundException exception,
            HttpServletRequest request
    ) {
        ProblemDetail problem = createProblem(
                HttpStatus.NOT_FOUND,
                NOT_FOUND_TYPE,
                "見つかりません",
                ApiErrorCode.NOT_FOUND,
                "旅行が見つかりません。",
                request
        );
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    @ExceptionHandler(InvalidAccessTokenException.class)
    ResponseEntity<ProblemDetail> handleInvalidAccessToken(
            InvalidAccessTokenException exception,
            HttpServletRequest request
    ) {
        ProblemDetail problem = createProblem(
                HttpStatus.NOT_FOUND,
                NOT_FOUND_TYPE,
                "見つかりません",
                ApiErrorCode.NOT_FOUND,
                "リンクが無効です。",
                request
        );
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    @ExceptionHandler(TokenRateLimitExceededException.class)
    ResponseEntity<ProblemDetail> handleRateLimit(
            TokenRateLimitExceededException exception,
            HttpServletRequest request
    ) {
        ProblemDetail problem = createProblem(
                HttpStatus.TOO_MANY_REQUESTS,
                RATE_LIMITED_TYPE,
                "試行回数が多すぎます",
                ApiErrorCode.RATE_LIMITED,
                "しばらく待ってから再試行してください。",
                request
        );
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", "900")
                .body(problem);
    }

    @ExceptionHandler(RecoveryConflictException.class)
    ResponseEntity<ProblemDetail> handleRecoveryConflict(
            RecoveryConflictException exception,
            HttpServletRequest request
    ) {
        ProblemDetail problem = createProblem(
                HttpStatus.CONFLICT,
                CONFLICT_TYPE,
                "競合が発生しました",
                ApiErrorCode.RESOURCE_CONFLICT,
                "このアカウントは既に別のメンバーへ関連付けられています。",
                request
        );
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    ResponseEntity<ProblemDetail> handleIdempotencyConflict(
            IdempotencyConflictException exception,
            HttpServletRequest request
    ) {
        ProblemDetail problem = createProblem(
                HttpStatus.CONFLICT,
                CONFLICT_TYPE,
                "競合が発生しました",
                ApiErrorCode.IDEMPOTENCY_CONFLICT,
                "同じIdempotency-Keyが異なるリクエストに使われています。",
                request
        );
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    @ExceptionHandler(TripForbiddenException.class)
    ResponseEntity<ProblemDetail> handleTripForbidden(
            TripForbiddenException exception,
            HttpServletRequest request
    ) {
        ProblemDetail problem = createProblem(
                HttpStatus.FORBIDDEN,
                FORBIDDEN_TYPE,
                "権限がありません",
                ApiErrorCode.FORBIDDEN,
                "この操作を実行する権限がありません。",
                request
        );
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(problem);
    }

    @ExceptionHandler(TripVersionConflictException.class)
    ResponseEntity<ProblemDetail> handleTripVersionConflict(
            TripVersionConflictException exception,
            HttpServletRequest request
    ) {
        ProblemDetail problem = createProblem(
                HttpStatus.CONFLICT,
                CONFLICT_TYPE,
                "競合が発生しました",
                ApiErrorCode.VERSION_CONFLICT,
                "旅行が別の操作で更新されています。",
                request
        );
        problem.setProperty("currentVersion", exception.current().version());
        problem.setProperty("current", exception.current());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    @ExceptionHandler(CandidateVersionConflictException.class)
    ResponseEntity<ProblemDetail> handleCandidateVersionConflict(
            CandidateVersionConflictException exception,
            HttpServletRequest request
    ) {
        ProblemDetail problem = createProblem(
                HttpStatus.CONFLICT,
                CONFLICT_TYPE,
                "競合が発生しました",
                ApiErrorCode.VERSION_CONFLICT,
                "候補が別の操作で更新されています。",
                request
        );
        problem.setProperty("currentVersion", exception.current().version());
        problem.setProperty("current", exception.current());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    @ExceptionHandler(SlotVersionConflictException.class)
    ResponseEntity<ProblemDetail> handleSlotVersionConflict(
            SlotVersionConflictException exception,
            HttpServletRequest request
    ) {
        ProblemDetail problem = createProblem(
                HttpStatus.CONFLICT,
                CONFLICT_TYPE,
                "競合が発生しました",
                ApiErrorCode.VERSION_CONFLICT,
                "枠が別の操作で更新されています。",
                request
        );
        problem.setProperty("currentVersion", exception.current().version());
        problem.setProperty("current", exception.current());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    @ExceptionHandler(VoteVersionConflictException.class)
    ResponseEntity<ProblemDetail> handleVoteVersionConflict(
            VoteVersionConflictException exception,
            HttpServletRequest request
    ) {
        ProblemDetail problem = createProblem(
                HttpStatus.CONFLICT,
                CONFLICT_TYPE,
                "競合が発生しました",
                ApiErrorCode.VERSION_CONFLICT,
                "投票が別の操作で更新されています。",
                request
        );
        problem.setProperty("currentVersion", exception.current().version());
        problem.setProperty("current", exception.current());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    @ExceptionHandler(ExpenseVersionConflictException.class)
    ResponseEntity<ProblemDetail> handleExpenseVersionConflict(
            ExpenseVersionConflictException exception,
            HttpServletRequest request
    ) {
        ProblemDetail problem = createProblem(
                HttpStatus.CONFLICT,
                CONFLICT_TYPE,
                "競合が発生しました",
                ApiErrorCode.VERSION_CONFLICT,
                "支出が別の操作で更新されています。",
                request
        );
        problem.setProperty("currentVersion", exception.current().version());
        problem.setProperty("current", exception.current());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    @ExceptionHandler(ExpenseStateConflictException.class)
    ResponseEntity<ProblemDetail> handleExpenseStateConflict(
            ExpenseStateConflictException exception,
            HttpServletRequest request
    ) {
        ProblemDetail problem = createProblem(
                HttpStatus.CONFLICT,
                CONFLICT_TYPE,
                "競合が発生しました",
                ApiErrorCode.RESOURCE_CONFLICT,
                exception.getMessage(),
                request
        );
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(
            Exception exception,
            HttpServletRequest request
    ) {
        String traceId = traceId(request);
        logger.error(
                "Unhandled API error traceId={} exceptionType={}",
                traceId,
                exception.getClass().getName()
        );
        ProblemDetail problem = createProblem(
                HttpStatus.INTERNAL_SERVER_ERROR,
                INTERNAL_ERROR_TYPE,
                "サーバーエラー",
                ApiErrorCode.INTERNAL_ERROR,
                "処理中に問題が発生しました。",
                request
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem);
    }

    private List<FieldErrorResponse> toFieldErrors(BindingResult bindingResult) {
        return bindingResult.getFieldErrors().stream()
                .map(error -> new FieldErrorResponse(
                        error.getField(),
                        errorMessage(error.getDefaultMessage())
                ))
                .sorted(Comparator.comparing(FieldErrorResponse::field))
                .limit(100)
                .toList();
    }

    private String parameterName(String discoveredName, int parameterIndex) {
        return discoveredName != null ? discoveredName : "arg" + parameterIndex;
    }

    private String errorMessage(String defaultMessage) {
        return defaultMessage != null ? defaultMessage : "入力値が不正です。";
    }

    private ProblemDetail createProblem(
            HttpStatus status,
            URI type,
            String title,
            ApiErrorCode code,
            String message,
            HttpServletRequest request
    ) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, message);
        problem.setType(type);
        problem.setTitle(title);
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", code.name());
        problem.setProperty("message", message);
        problem.setProperty("traceId", traceId(request));
        return problem;
    }

    private String traceId(HttpServletRequest request) {
        Object traceId = request.getAttribute(TraceIdFilter.REQUEST_ATTRIBUTE);
        return traceId instanceof String value ? value : "unavailable";
    }
}
