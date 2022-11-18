package com.task2.orderservice.service;

import com.task2.orderservice.dto.InventoryResponse;
import com.task2.orderservice.dto.OrderLineItemsDto;
import com.task2.orderservice.dto.OrderRequest;
import com.task2.orderservice.event.OrderPlacedEvent;
import com.task2.orderservice.model.Order;
import com.task2.orderservice.model.OrderLineItems;
import com.task2.orderservice.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.sleuth.Span;
import org.springframework.cloud.sleuth.Tracer;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class OrderService {

    private final OrderRepository orderRepository;
    private final WebClient.Builder webClientBuilder;

    private final Tracer tracer;

    private final KafkaTemplate<String, OrderPlacedEvent> kafkaTemplate;
    public String placeOrder(OrderRequest orderRequest)
    {
        Order order = new Order();
        order.setOrderNumber(UUID.randomUUID().toString());

        List<OrderLineItems> orderLineItems = orderRequest.getOrderLineItemsDtoList()
                .stream()
                .map(orderLineItemsDto -> mapToDto(orderLineItemsDto)).collect(Collectors.toList());
        order.setOrderLineItemsList(orderLineItems);

        List<String> skuCodes = order.getOrderLineItemsList().stream().map(orderLineItem -> orderLineItem.getSkuCode()).collect(Collectors.toList());
        //call Inventory service if product is in stock
        log.info("Calling inventory service");

        Span inventoryServiceLookup = tracer.nextSpan().name("InventoryServiceLookup");

       try( Tracer.SpanInScope spanInScope = tracer.withSpan(inventoryServiceLookup.start()))
       {
           InventoryResponse[] inventoryResponsesArray = webClientBuilder.build().get()
                   .uri("http://inventory-service/api/inventory", uriBuilder -> uriBuilder.queryParam("skuCode", skuCodes).build())
                   .retrieve()
                   .bodyToMono(InventoryResponse[].class)
                   .block();

           boolean allProductsInStock = Arrays.stream(inventoryResponsesArray).allMatch(inventoryResponse -> inventoryResponse.isInStock());

           if(allProductsInStock) {
               orderRepository.save(order);
               kafkaTemplate.send("notificationTopic", new OrderPlacedEvent(order.getOrderNumber()));
               return "Order is placed successfully";
           }else
           {
               throw new IllegalArgumentException("Product is not in stock , please try again later");
           }
       }finally {
           inventoryServiceLookup.end();
       }
    }

    private OrderLineItems mapToDto(OrderLineItemsDto orderLineItemsDto) {
        OrderLineItems orderLineItems = new OrderLineItems();
        orderLineItems.setPrice(orderLineItemsDto.getPrice());
        orderLineItems.setQuantity(orderLineItemsDto.getQuantity());
        orderLineItems.setSkuCode(orderLineItemsDto.getSkuCode());
        return orderLineItems;
    }
}
