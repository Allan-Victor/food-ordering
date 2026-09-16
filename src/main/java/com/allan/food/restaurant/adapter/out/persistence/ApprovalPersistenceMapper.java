package com.allan.food.restaurant.adapter.out.persistence;

import com.allan.food.restaurant.domain.model.OrderApproval;

import java.util.List;

/**
 * Translates between {@link OrderApproval} and its row shape.
 *
 * <p><b>Simpler than its siblings in one telling way: there is no version to round-trip.</b> The aggregate is
 * immutable, so the mapper only ever flattens a fresh decision or rebuilds a settled one. When a mapper turns
 * out this small, it is usually the aggregate telling you something about its lifecycle.
 */
final class ApprovalPersistenceMapper {

    private ApprovalPersistenceMapper() {
        throw new AssertionError("No instances.");
    }

    static OrderApprovalJpaEntity toJpaEntity(OrderApproval approval) {
        return OrderApprovalJpaEntity.builder()
                .id(approval.approvalId())
                .orderId(approval.orderId())
                .restaurantId(approval.restaurantId())
                .status(approval.status())
                .reasons(List.copyOf(approval.reasons()))
                .decidedAt(approval.decidedAt())
                .build();
    }

    static OrderApproval toDomain(OrderApprovalJpaEntity entity) {
        return OrderApproval.reconstitute(
                entity.getId(),
                entity.getOrderId(),
                entity.getRestaurantId(),
                entity.getStatus(),
                List.copyOf(entity.getReasons()),
                entity.getDecidedAt(),
                0L);   // no version column; the aggregate is never updated
    }
}
