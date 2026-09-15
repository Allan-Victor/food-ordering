package com.allan.food.order.application.saga;

/**
 * The three steps of the order saga, each classified by Richardson's transaction taxonomy.
 *
 * <p><b>Why this enum exists at all.</b> It has no behaviour and nothing branches on it today — it could be a
 * paragraph of prose. It is code because the classification is the single most important design fact about a
 * saga, and prose rots while an enum referenced from the orchestrator's handlers does not. When someone adds a
 * fourth step in three years, this is the list they must place it in, and placing it forces them to answer the
 * question that matters: can this be undone?
 *
 * <p><b>The ordering rule.</b> Compensatable steps come first, then the pivot, then retriables. That is not a
 * stylistic preference — it is what makes a saga recoverable. Once the pivot commits, backward recovery is no
 * longer available, so every step that <i>might</i> need undoing must already have happened.
 *
 * <p><b>Why this lives in the application layer, not the domain.</b> Vernon classifies a saga as a Process
 * Manager: it coordinates work across aggregates and bounded contexts but owns no invariants of its own. The
 * rules it enforces are about <i>sequence</i>, not about state consistency, and every invariant it relies on
 * ({@code PAID} only from {@code PENDING}, and so on) is already enforced by {@code Order}. A process manager
 * in the domain layer would be a domain object that orchestrates I/O, which is a contradiction.
 */
enum OrderSagaStep {
    /**
     * Charge the customer.
     *
     * <p><b>Compensatable</b> — a completed charge can be refunded, so a later failure can undo it. Failure of
     * this step itself needs no compensation: nothing before it has external effect, so the order moves
     * directly to {@code CANCELLED}.
     */
    PROCESS_PAYMENT(SagaStepType.COMPENSATABLE),

    /**
     * Ask the restaurant to accept and begin preparing the order.
     *
     * <p><b>Pivot</b> — the go/no-go point. Approval means food is being cooked, and no message we send
     * afterwards can un-cook it. So this is the last step whose failure can trigger backward recovery: a
     * rejection compensates the payment and cancels the order. Its success commits the saga to running forward
     * to completion.
     *
     * <p>Identifying the pivot correctly is the hardest judgement in saga design, and the test is physical, not
     * technical: at what point does the system cause an effect in the world that software cannot reverse?
     */
    APPROVE_RESTAURANT(SagaStepType.PIVOT),

    /**
     * Move the order to {@code APPROVED}.
     *
     * <p><b>Retriable</b> — it happens after the pivot, so failure is not an option in the literal sense: there
     * is no compensation available, because the restaurant is already cooking. A failure here must be retried
     * until it succeeds, and a step that cannot be made to succeed by retrying does not belong after a pivot.
     *
     * <p>In this saga the step is a local state transition on an aggregate we already hold, so it cannot
     * realistically fail. That is not an accident — designing retriable steps to be trivially succeedable is
     * how you keep the tail of a saga safe.
     */
    APPROVE_ORDER(SagaStepType.RETRIABLE);

    private final SagaStepType type;

    OrderSagaStep(SagaStepType type) {
        this.type = type;
    }

    public SagaStepType type() {
        return type;
    }

    /**
     * Richardson's classification of a saga transaction.
     *
     * <p>Kept as a nested type rather than its own file: it has no meaning apart from the steps it classifies,
     * and nesting says so.
     */
    enum SagaStepType {

        /** Reversible by a compensating transaction. Must precede the pivot. */
        COMPENSATABLE,

        /** The go/no-go point. Its commit removes backward recovery as an option. */
        PIVOT,

        /** Follows the pivot. Has no compensation, so it must be retried until it succeeds. */
        RETRIABLE

    }

}
