package com.allan.food.order.adapter.in.web;

import com.allan.food.order.application.exception.OrderNotFoundException;
import com.allan.food.order.application.exception.RestaurantNotFoundException;
import com.allan.food.order.application.port.in.CreateOrderUseCase;
import com.allan.food.order.application.port.in.TrackOrderUseCase;
import com.allan.food.order.application.port.in.command.CreateOrderCommand;
import com.allan.food.order.application.port.in.command.CreateOrderResult;
import com.allan.food.order.application.port.in.command.TrackOrderQuery;
import com.allan.food.order.application.port.in.command.TrackOrderResponse;
import com.allan.food.order.domain.exception.OrderDomainException;
import com.allan.food.order.domain.model.valueobject.OrderStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

import static com.allan.food.order.OrderTestData.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Web adapter tests through a mock servlet environment.
 *
 * <p><b>{@code @WebMvcTest} loads only the web slice</b> — this controller, the exception handler, Jackson, and
 * the validation infrastructure. The use-case ports are replaced with mocks, so nothing below the boundary
 * runs. That is the correct scope: what is under test is HTTP translation, not order logic.
 *
 * <p><b>{@code @MockitoBean}, not {@code @MockBean}.</b> The latter was deprecated in Spring Boot 3.4 and
 * removed in 4.0; the replacement lives in {@code org.springframework.test.context.bean.override.mockito}.
 * Worth knowing, because most material online still shows the old annotation.
 *
 * <p>Mocking the <i>ports</i> rather than the service implementations is what makes this possible at all — the
 * services are package-private and invisible from here, exactly as intended.
 */
@WebMvcTest(OrderController.class)
@DisplayName("OrderController")
class OrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private CreateOrderUseCase createOrderUseCase;

    @MockitoBean
    private TrackOrderUseCase trackOrderUseCase;

    private static String validBody() {
        return """
                {
                  "customerId": "%s",
                  "restaurantId": "%s",
                  "items": [
                    {"productId": "%s", "quantity": 2},
                    {"productId": "%s", "quantity": 3}
                  ],
                  "address": {"street": "Plot 12 Kira Road", "postalCode": "256", "city": "Kampala"}
                }
                """.formatted(CUSTOMER_ID, RESTAURANT_ID, ROLEX_ID, CHAPATI_ID);
    }

    @Test
    @DisplayName("201 with a Location header pointing at the tracking endpoint")
    void createReturns201AndLocation() throws Exception {
        UUID trackingId = UUID.randomUUID();
        when(createOrderUseCase.createOrder(any())).thenReturn(new CreateOrderResult(trackingId));

        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location",
                        "http://localhost/api/v1/orders/" + trackingId))
                .andExpect(jsonPath("$.trackingId").value(trackingId.toString()));
    }

    @Test
    @DisplayName("translates the wire shape into a command without inventing or dropping anything")
    void mapsRequestToCommand() throws Exception {
        when(createOrderUseCase.createOrder(any())).thenReturn(new CreateOrderResult(UUID.randomUUID()));

        mockMvc.perform(post("/api/v1/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBody()));

        ArgumentCaptor<CreateOrderCommand> captor = ArgumentCaptor.forClass(CreateOrderCommand.class);
        org.mockito.Mockito.verify(createOrderUseCase).createOrder(captor.capture());

        CreateOrderCommand command = captor.getValue();
        assertThat(command.customerId()).isEqualTo(CUSTOMER_ID);
        assertThat(command.restaurantId()).isEqualTo(RESTAURANT_ID);
        assertThat(command.items())
                .extracting(CreateOrderCommand.OrderItemDto::productId, CreateOrderCommand.OrderItemDto::quantity)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(ROLEX_ID, 2),
                        org.assertj.core.groups.Tuple.tuple(CHAPATI_ID, 3));
        assertThat(command.address().city()).isEqualTo("Kampala");
    }

    @Test
    @DisplayName("200 with the status as a string, not the enum's serialised form")
    void trackReturnsStatusAsString() throws Exception {
        UUID trackingId = UUID.randomUUID();
        when(trackOrderUseCase.trackOrder(new TrackOrderQuery(trackingId)))
                .thenReturn(new TrackOrderResponse(trackingId, OrderStatus.CANCELLING, List.of("provider timeout")));

        mockMvc.perform(get("/api/v1/orders/{trackingId}", trackingId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trackingId").value(trackingId.toString()))
                .andExpect(jsonPath("$.status").value("CANCELLING"))
                .andExpect(jsonPath("$.failureMessages[0]").value("provider timeout"));
    }

    @Test
    @DisplayName("400 with per-field errors when the body fails validation")
    void invalidBodyReturns400WithFieldErrors() throws Exception {
        String zeroQuantity = validBody().replace("\"quantity\": 2", "\"quantity\": 0");

        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(zeroQuantity))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors").exists());

        // Rejected at the wire boundary — the use case is never reached.
        verifyNoInteractions(createOrderUseCase);
    }

    @Test
    @DisplayName("400 when a required field is missing entirely")
    void missingFieldReturns400() throws Exception {
        String noAddress = """
                {"customerId": "%s", "restaurantId": "%s",
                 "items": [{"productId": "%s", "quantity": 1}]}
                """.formatted(CUSTOMER_ID, RESTAURANT_ID, ROLEX_ID);

        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(noAddress))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.address").exists());
    }

    @Test
    @DisplayName("400 on an empty item list — an order must have something in it")
    void emptyItemsReturns400() throws Exception {
        String noItems = """
            {
              "customerId": "%s",
              "restaurantId": "%s",
              "items": [],
              "address": {"street": "Plot 12 Kira Road", "postalCode": "256", "city": "Kampala"}
            }
            """.formatted(CUSTOMER_ID, RESTAURANT_ID);

        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(noItems))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(createOrderUseCase);
    }

    @Test
    @DisplayName("404 as a problem document when the tracking id is unknown")
    void unknownOrderReturns404Problem() throws Exception {
        UUID unknown = UUID.randomUUID();
        when(trackOrderUseCase.trackOrder(any())).thenThrow(new OrderNotFoundException(unknown));

        mockMvc.perform(get("/api/v1/orders/{trackingId}", unknown))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Order not found"))
                .andExpect(jsonPath("$.type").value("https://allan.food/problems/order-not-found"))
                .andExpect(jsonPath("$.trackingId").value(unknown.toString()));
    }

    @Test
    @DisplayName("422, not 404, when the body references an unknown restaurant")
    void unknownRestaurantReturns422() throws Exception {
        when(createOrderUseCase.createOrder(any()))
                .thenThrow(new RestaurantNotFoundException(RESTAURANT_ID));

        // The endpoint exists and was addressed correctly; it is the content that cannot be
        // processed. Returning 404 would tell the client the wrong thing entirely.
        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.restaurantId").value(RESTAURANT_ID.toString()));
    }

    @Test
    @DisplayName("409 when a domain rule refuses the order")
    void domainRefusalReturns409() throws Exception {
        when(createOrderUseCase.createOrder(any()))
                .thenThrow(new OrderDomainException("Restaurant %s is not currently accepting orders"
                        .formatted(RESTAURANT_ID)));

        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Order cannot be placed"))
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("not currently accepting orders")));
    }

    @Test
    @DisplayName("400 on a malformed path variable, before the controller is entered")
    void malformedTrackingIdReturns400() throws Exception {
        mockMvc.perform(get("/api/v1/orders/{trackingId}", "not-a-uuid"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(trackOrderUseCase);
    }

    @Test
    @DisplayName("malformed JSON produces a problem document, not a different error shape")
    void malformedJsonReturnsProblemDetail() throws Exception {
        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ this is not json"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        // This is what extending ResponseEntityExceptionHandler buys: Spring's own exceptions
        // render in the same shape as ours, so a client sees one error format, not two.
    }
}
