package com.allan.food.order.adapter.in.web;

import com.allan.food.order.application.exception.OrderNotFoundException;
import com.allan.food.order.application.exception.RestaurantNotFoundException;
import com.allan.food.order.domain.exception.OrderDomainException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Translates exceptions into RFC 9457 problem responses. The single place in the codebase where a failure
 * becomes an HTTP status.
 *
 * <p><b>Why {@code ProblemDetail} and not a custom envelope.</b> A widespread pattern — the
 * hogwarts-artifacts-online sample among many — wraps every response in a bespoke
 * {@code {flag, code, message, data}} shape. It predates Spring 6 and duplicates in the body what the status
 * line already carries, so clients end up parsing two error channels that can disagree. {@code ProblemDetail}
 * is a registered media type ({@code application/problem+json}) with defined semantics for {@code type},
 * {@code title}, {@code status}, {@code detail} and {@code instance}, plus an extension mechanism for
 * everything else. Any client library that speaks the standard understands it without bespoke code.
 *
 * <p><b>Why extend {@code ResponseEntityExceptionHandler}.</b> It already renders Spring's own exceptions —
 * malformed JSON, unsupported media type, missing parameters — as {@code ProblemDetail}. Without it those
 * failures return a different shape than ours, and a client sees two error formats from one API depending on
 * how badly it got the request wrong. (Setting {@code spring.mvc.problemdetails.enabled=true} achieves the
 * same for the built-ins; extending the base class does it while leaving room to override individual handlers,
 * which we need below.)
 *
 * <p><b>Scoped to this package</b> via {@code basePackageClasses}. In a modular monolith a global advice
 * silently becomes shared infrastructure between contexts; scoping keeps each context's boundary translation
 * its own, so a second context can map the same exception differently.
 *
 * <p><b>Logging lives here, and only here.</b> The reference logs a warning and then throws from inside its
 * data-access code, so one failure is recorded twice at two severities with no added information. A failure
 * should be logged once, at the point where its disposition is finally known — which is exactly here. Client
 * errors log at {@code warn} without a stack trace, since a 404 is not a defect and its trace is noise;
 * anything unhandled logs at {@code error} with the full trace, because it is.
 */
@RestControllerAdvice(basePackageClasses = OrderController.class)
class OrderExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(OrderExceptionHandler.class);

    /**
     * Base for the {@code type} URI, which RFC 9457 uses as the machine-readable identity of a problem kind.
     * Clients should branch on this, never on {@code detail} — prose is for humans and changes freely.
     */
    private static final String PROBLEM_BASE = "https://allan.food/problems/";

    /**
     * 404: the addressed resource does not exist.
     *
     * <p>The tracking id goes into an extension property as well as the prose, so a client can react
     * programmatically without parsing an English sentence — which is the reason
     * {@link OrderNotFoundException} carries the {@code UUID} as a field rather than only in its message.
     */
    @ExceptionHandler(OrderNotFoundException.class)
    ProblemDetail handleOrderNotFound(OrderNotFoundException ex) {
        log.warn("Order tracking lookup failed: {}", ex.getMessage());

        ProblemDetail problem = problem(HttpStatus.NOT_FOUND, "Order not found", ex.getMessage(), "order-not-found");
        problem.setProperty("trackingId", ex.getTrackingId());
        return problem;
    }

    /**
     * 422: the request was well-formed but references a restaurant that does not exist.
     *
     * <p><b>Not 404, and the distinction is worth defending.</b> The endpoint exists and was addressed
     * correctly; what is unprocessable is the content. Returning 404 here would tell a client the wrong thing —
     * that {@code POST /api/v1/orders} is missing — and is why we gave this its own exception type rather than
     * reusing the domain exception as the reference does.
     */
    @ExceptionHandler(RestaurantNotFoundException.class)
    ProblemDetail handleRestaurantNotFound(RestaurantNotFoundException ex) {
        log.warn("Order rejected, unknown restaurant: {}", ex.getMessage());

        ProblemDetail problem = problem(HttpStatus.UNPROCESSABLE_ENTITY, "Unknown restaurant", ex.getMessage(),
                "restaurant-not-found");
        problem.setProperty("restaurantId", ex.getRestaurantId());
        return problem;
    }

    /**
     * 409: a domain invariant refused the request.
     *
     * <p>An inactive restaurant, a product not on the menu, a state transition the aggregate does not permit.
     * Conflict is the honest status: the request is intelligible and the client is entitled to make it, but the
     * current state of the system does not allow it. Retrying unchanged will not help; something must change
     * first.
     *
     * <p><b>The message is passed through, and that is a decision with a cost.</b> Domain exception messages are
     * written for developers and can leak internal vocabulary. We accept it because these messages are the most
     * useful thing we can tell a client about why an order was refused, and because our domain messages are
     * written with that in mind. A system handling untrusted callers would map known cases to curated text and
     * return something generic otherwise.
     */
    @ExceptionHandler(OrderDomainException.class)
    ProblemDetail handleDomainViolation(OrderDomainException ex) {
        log.warn("Order rejected by domain rule: {}", ex.getMessage());
        return problem(HttpStatus.CONFLICT, "Order cannot be placed", ex.getMessage(), "domain-rule-violated");
    }

    /**
     * 400 with per-field detail, for a request body that failed bean validation.
     *
     * <p>Overridden rather than left to the base class so the response carries an {@code errors} map of field
     * path to message. A client fixing a form needs to know <i>which</i> field, and reconstructing that from
     * prose is not something an API should ask of it.
     *
     * <p>The signature returns {@code ResponseEntity<Object>} because that is what the base class declares;
     * {@code ex.getBody()} hands back the {@code ProblemDetail} Spring already built, so we enrich rather than
     * replace it and keep the framework's own {@code type} and {@code title}.
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        Map<String, String> errors = ex.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(
                        FieldError::getField,
                        fieldError -> fieldError.getDefaultMessage() == null ? "invalid" : fieldError.getDefaultMessage(),
                        (first, second) -> first));
        log.warn("Rejected malformed order request: {}", errors);

        ProblemDetail problem = ex.getBody();
        problem.setDetail("The request contains invalid fields.");
        problem.setProperty("errors", errors);
        problem.setProperty("timestamp", Instant.now());

        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    /**
     * 500: a command reached a use-case port in an invalid state.
     *
     * <p><b>Reaching this handler means we have a defect, not that the caller misbehaved</b> — which is why it
     * is a 500 and not a 400. The web request is validated at {@link CreateOrderRequest} before a command is
     * ever built, so a violation detected later by the port's own {@code @Validated} guard means this adapter
     * constructed something the port forbids: a constraint present on the command but missing from the request,
     * or a mapping bug. Telling the client it sent a bad request would send them hunting for a mistake they did
     * not make.
     *
     * <p>The violation text is deliberately withheld from the response and written to the log instead. It
     * describes internal parameter paths, which are of no use to a client and are the sort of detail that
     * should not leave the process.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    ProblemDetail handleCommandContractViolation(ConstraintViolationException ex) {
        log.error("Adapter built a command violating the port contract — this is a defect", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error",
                "The request could not be processed.", "internal-error");
    }

    /**
     * Last resort for anything unanticipated.
     *
     * <p>Logged with its stack trace, and answered with nothing but a generic message: exception text can
     * carry table names, SQL fragments and file paths, none of which belongs in a response body. The
     * information the operator needs goes to the log; the client gets a status and a shrug.
     */
    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception ex) {
        log.error("Unhandled exception serving order request", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error",
                "The request could not be processed.", "internal-error");
    }




















    /** Assembles a problem with our {@code type} URI convention and a timestamp extension. */
    private static ProblemDetail problem(HttpStatus status, String title, String detail, String typeSlug) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setType(URI.create(PROBLEM_BASE + typeSlug));
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }
}
