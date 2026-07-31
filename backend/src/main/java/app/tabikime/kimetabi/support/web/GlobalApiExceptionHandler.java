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

@RestControllerAdvice
public class GlobalApiExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalApiExceptionHandler.class);
    private static final URI VALIDATION_TYPE =
            URI.create("https://tabikime.app/problems/validation-failed");
    private static final URI INVALID_REQUEST_TYPE =
            URI.create("https://tabikime.app/problems/invalid-request");
    private static final URI INTERNAL_ERROR_TYPE =
            URI.create("https://tabikime.app/problems/internal-error");

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
