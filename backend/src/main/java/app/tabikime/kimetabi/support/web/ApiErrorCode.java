package app.tabikime.kimetabi.support.web;

enum ApiErrorCode {
    INVALID_REQUEST,
    VALIDATION_FAILED,
    NOT_FOUND,
    FORBIDDEN,
    RATE_LIMITED,
    RESOURCE_CONFLICT,
    VERSION_CONFLICT,
    IDEMPOTENCY_CONFLICT,
    INTERNAL_ERROR
}
