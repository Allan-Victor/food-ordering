package com.allan.food.saga.contract;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The message contract between the order saga and its participants.
 *
 * <p><b>Why a shared module here, when we rejected the reference's shared kernel.</b> Gelenler's
 * {@code common-domain} shares {@code Money}, {@code OrderStatus} and base entity classes across every service
 * — that is Evans' shared kernel, and it couples the internal models of independent contexts so that one
 * cannot evolve without the others. This module shares something different in kind: an <i>integration
 * contract</i>. Messages between services are inherently shared; the only question is whether the sharing is
 * explicit and versioned or implicit and accidental. Making it a module with nothing but records is the
 * explicit version.
 *
 * <p><b>Which is why nothing here is a domain type.</b> Amounts are {@code BigDecimal} plus a currency code,
 * not {@code Money}. Statuses do not appear at all. When Payment becomes its own deployable at Slice 4 it will
 * not be able to import the Order context's {@code Money}, and a contract that assumed it could would have to
 * be rewritten at exactly the wrong moment. Primitives at the boundary, domain types inside it — the same rule
 * that kept JPA out of {@code Order}.
 *
 * <p><b>On the two identifiers.</b> {@code orderId} is what the orchestrator correlates on: a reply arrives, we
 * load that order, we advance the state machine. {@code sagaId} identifies the saga <i>instance</i> and is not
 * used for lookup in Slice 2 — it goes into the logging MDC so one saga's journey across three modules is
 * greppable as a unit. Carrying it now is not speculation but schema economics: adding a field to a contract
 * three services share is cheap today and a coordinated deployment later.
 *
 * <p>Every message carries a timestamp, which is what makes stuck-saga detection possible without any
 * additional bookkeeping.
 */
public final class SagaContract {

    private SagaContract() {
        throw new AssertionError("No instances");
    }

    // ─────────────────────────────────────────────────────────────
    //  Commands — issued by the orchestrator to a participant
    // ─────────────────────────────────────────────────────────────

    /**
     * Charge the customer for a placed order. First step; compensatable by {@link RefundPayment}.
     */
    public record ProcessPayment(
            UUID sagaId,
            UUID orderId,
            UUID customerId,
            BigDecimal amount,
            String currency,
            Instant issuedAt) {

    }

    /**
     * Reverse a completed charge.
     *
     * <p>The compensating transaction for {@link ProcessPayment}, issued when the restaurant rejects an order
     * that has already been paid for. Note it names the order rather than a payment id: the participant owns
     * the mapping from order to payment, and requiring the orchestrator to track a payment id would leak the
     * participant's internal identity into the saga.
     */
    public record RefundPayment(
            UUID sagaId,
            UUID orderId,
            UUID customerId,
            BigDecimal amount,
            String currency,
            Instant issuedAt) {
    }

    /**
     * Ask the restaurant to accept the order and begin preparing it. The pivot step.
     *
     * <p>Carries the lines because the restaurant needs to know what to cook — and carries them as ids and
     * quantities only. Prices are the ordering context's concern; the kitchen has no use for them.
     */
    public record ApproveOrder(
            UUID sagaId,
            UUID orderId,
            UUID restaurantId,
            List<Line> lines,
            Instant issuedAt) {

        /**
         * Defensive copy — a command in flight must not change under the participant.
         */
        public ApproveOrder {
            lines = List.copyOf(lines);
        }
        /** One thing to prepare, and how many of it. */
        public record Line(UUID productId, int quantity) {
        }

    }


    // ─────────────────────────────────────────────────────────────
    //  Replies — sent by a participant back to the orchestrator
    // ─────────────────────────────────────────────────────────────

    /**
     * The charge succeeded.
     *
     * <p>Carries the participant's {@code paymentId} purely so it appears in the orchestrator's logs and in the
     * order's audit trail; the saga never sends it back. Cross-context identifiers are for humans reading
     * traces, not for the coordinator to hold as state.
     */
    public record PaymentCompleted(
            UUID sagaId,
            UUID orderId,
            UUID paymentId,
            Instant occurredAt) {}

    /**
     * The charge failed. No compensation is required — nothing irreversible has happened yet — so the saga
     * cancels the order outright.
     */
    public record PaymentFailed(
            UUID sagaId,
            UUID orderId,
            List<String> reasons,
            Instant occurredAt) {

        public PaymentFailed {
            reasons = List.copyOf(reasons);
        }
    }

    /**
     * A compensating refund completed. Releases the order from {@code CANCELLING} to {@code CANCELLED}.
     *
     * <p>There is deliberately no {@code RefundFailed}. A compensating transaction must be retriable by
     * definition: if it could fail permanently, the saga would have no way back and the step it compensates
     * should never have been classified as compensatable. A refund that will not settle is an operational
     * incident, not a saga branch.
     */
    public record PaymentRefunded(
            UUID sagaId,
            UUID orderId,
            Instant occurredAt) {}

    /**
     * The restaurant accepted the order. The pivot has committed — from here the saga only moves forward.
     */
    public record OrderApproved(
            UUID sagaId,
            UUID orderId,
            Instant occurredAt) {}

    /**
     * The restaurant refused the order. The last failure that can trigger backward recovery: the saga
     * compensates the payment and then cancels the order.
     */
    public record OrderRejected(
            UUID sagaId,
            UUID orderId,
            List<String> reasons,
            Instant occurredAt) {

        public OrderRejected {
            reasons = List.copyOf(reasons);
        }
    }
}







