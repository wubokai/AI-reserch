package com.aiquantresearch.api.shared.web;

import com.aiquantresearch.api.research.application.ResearchApplicationException;
import com.fasterxml.jackson.databind.JsonMappingException;
import jakarta.validation.ConstraintViolationException;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class GlobalApiExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalApiExceptionHandler.class);

    private final Clock clock;

    @Autowired
    public GlobalApiExceptionHandler(ObjectProvider<Clock> clockProvider) {
        this(clockProvider.getIfAvailable(Clock::systemUTC));
    }

    GlobalApiExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiErrorResponse> handleApiException(ApiException exception) {
        return response(
                exception.errorCode(),
                exception.getMessage(),
                exception.researchId(),
                exception.details()
        );
    }

    @ExceptionHandler(ResearchApplicationException.class)
    ResponseEntity<ApiErrorResponse> handleResearchApplicationException(
            ResearchApplicationException exception
    ) {
        ApiErrorCode classification = classify(exception);
        return response(
                classification,
                exception.code(),
                exception.getMessage(),
                exception.researchId(),
                exception.retryable(),
                Map.of()
        );
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiErrorResponse> handleRouteNotFound(NoResourceFoundException exception) {
        return response(
                ApiErrorCode.ROUTE_NOT_FOUND,
                "No API route matches this request",
                null,
                Map.of()
        );
    }

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            BindException.class,
            MissingRequestHeaderException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class,
            HandlerMethodValidationException.class,
            ConstraintViolationException.class
    })
    ResponseEntity<ApiErrorResponse> handleInvalidRequest(Exception exception) {
        return response(
                ApiErrorCode.INVALID_REQUEST,
                "The request did not satisfy the API contract",
                null,
                validationDetails(exception)
        );
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ApiErrorResponse> handleAuthentication(AuthenticationException exception) {
        return response(
                ApiErrorCode.UNAUTHORIZED,
                "An authenticated principal is required",
                null,
                Map.of()
        );
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiErrorResponse> handleAccessDenied(AccessDeniedException exception) {
        return response(ApiErrorCode.FORBIDDEN, "The current principal cannot access this resource", null, Map.of());
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<ApiErrorResponse> handleOptimisticLock(OptimisticLockingFailureException exception) {
        return response(
                ApiErrorCode.OPTIMISTIC_LOCK_CONFLICT,
                "The resource changed while this request was being processed",
                null,
                Map.of()
        );
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiErrorResponse> handleDataIntegrity(DataIntegrityViolationException exception) {
        LOGGER.warn("Database constraint rejected an API operation", exception);
        return response(
                ApiErrorCode.INVALID_STATE_TRANSITION,
                "The operation conflicts with the current persisted state",
                null,
                Map.of()
        );
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiErrorResponse> handleUnexpected(Exception exception) {
        LOGGER.error("Unhandled API exception", exception);
        return response(
                ApiErrorCode.INTERNAL_ERROR,
                "The request could not be completed",
                null,
                Map.of()
        );
    }

    private ResponseEntity<ApiErrorResponse> response(
            ApiErrorCode code,
            String message,
            java.util.UUID researchId,
            Map<String, Object> details
    ) {
        return response(
                code,
                code.name(),
                message,
                researchId,
                code.retryable(),
                details
        );
    }

    private ResponseEntity<ApiErrorResponse> response(
            ApiErrorCode classification,
            String publicCode,
            String message,
            java.util.UUID researchId,
            boolean retryable,
            Map<String, Object> details
    ) {
        String requestId = MDC.get(RequestIdFilter.REQUEST_ID_MDC_KEY);
        if (requestId == null) {
            requestId = "req_unavailable";
        }
        ApiErrorResponse body = new ApiErrorResponse(
                classification.problemType(),
                classification.title(),
                clock.instant(),
                classification.status().value(),
                publicCode,
                message,
                requestId,
                researchId,
                retryable,
                details
        );
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(classification.status())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON);
        if (classification == ApiErrorCode.SERVICE_UNAVAILABLE) {
            builder.header("Retry-After", "2");
        }
        return builder.body(body);
    }

    private Map<String, Object> validationDetails(Exception exception) {
        Map<String, Object> details = new LinkedHashMap<>();
        if (exception instanceof MethodArgumentNotValidException validationException) {
            Map<String, String> fields = new LinkedHashMap<>();
            validationException.getBindingResult().getFieldErrors().forEach(error ->
                    fields.putIfAbsent(error.getField(), error.getDefaultMessage())
            );
            details.put("fields", fields);
        } else if (exception instanceof MissingRequestHeaderException headerException) {
            details.put("missingHeader", headerException.getHeaderName());
        } else if (exception instanceof MethodArgumentTypeMismatchException mismatchException) {
            details.put("parameter", mismatchException.getName());
        } else if (exception instanceof MissingServletRequestParameterException parameterException) {
            details.put("missingParameter", parameterException.getParameterName());
        } else if (exception instanceof ConstraintViolationException violationException) {
            Map<String, String> fields = new LinkedHashMap<>();
            violationException.getConstraintViolations().forEach(violation ->
                    fields.putIfAbsent(
                            violation.getPropertyPath().toString(),
                            violation.getMessage()
                    )
            );
            details.put("fields", fields);
        } else if (exception instanceof HttpMessageNotReadableException unreadableException
                && unreadableException.getCause() instanceof JsonMappingException mappingException) {
            details.put("jsonPath", mappingException.getPathReference());
        }
        return details;
    }

    private static ApiErrorCode classify(ResearchApplicationException exception) {
        return switch (exception.code()) {
            case "INVALID_REQUEST" -> ApiErrorCode.INVALID_REQUEST;
            case "INVALID_SYMBOL" -> ApiErrorCode.INVALID_SYMBOL;
            case "SECURITY_MISMATCH" -> ApiErrorCode.SECURITY_MISMATCH;
            case "RESEARCH_NOT_FOUND" -> ApiErrorCode.RESEARCH_NOT_FOUND;
            case "ACCOUNT_DISABLED" -> ApiErrorCode.ACCOUNT_DISABLED;
            case "INVALID_STATE_TRANSITION" -> ApiErrorCode.INVALID_STATE_TRANSITION;
            case "IDEMPOTENCY_KEY_REUSED" -> ApiErrorCode.IDEMPOTENCY_KEY_REUSED;
            case "IDEMPOTENCY_REQUEST_IN_PROGRESS", "IDEMPOTENCY_IN_PROGRESS" ->
                    ApiErrorCode.IDEMPOTENCY_REQUEST_IN_PROGRESS;
            case "IDEMPOTENCY_CONFLICT" -> ApiErrorCode.IDEMPOTENCY_CONFLICT;
            case "SERVICE_UNAVAILABLE", "PROVIDER_UNAVAILABLE",
                    "IDEMPOTENCY_RESERVATION_FAILED", "IDEMPOTENCY_COMPLETION_FAILED" ->
                    ApiErrorCode.SERVICE_UNAVAILABLE;
            default -> exception.retryable()
                    ? ApiErrorCode.SERVICE_UNAVAILABLE
                    : ApiErrorCode.INTERNAL_ERROR;
        };
    }
}
