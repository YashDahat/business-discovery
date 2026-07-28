package com.business.discovery.worker.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class JpaBidirectionalSavePatcherTest {

    private static final String ORDER_ENTITY = """
            package com.x.model;
            import jakarta.persistence.*;
            import java.util.List;
            @Entity
            public class Order {
                @Id private Long id;
                @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
                private List<OrderItem> items;
            }
            """;

    private static final String ORDER_ITEM_ENTITY = """
            package com.x.model;
            import jakarta.persistence.*;
            @Entity
            public class OrderItem {
                @Id private Long id;
                @ManyToOne
                @JoinColumn(name = "order_id", nullable = false)
                private Order order;
            }
            """;

    /** The buggy circuit-house shape: collection set, parent saved, back-ref never set before save. */
    private static final String BUGGY_SERVICE = """
            package com.x.service;
            public class OrderService {
                public Order createOrder(java.util.List<OrderItem> orderItems) {
                    Order order = new Order();
                    order.setItems(orderItems);
                    Order savedOrder = orderRepository.save(order);
                    return savedOrder;
                }
            }
            """;

    private Path scaffold(Path root, String service) throws IOException {
        Path model = root.resolve("com/x/model");
        Path svc = root.resolve("com/x/service");
        Files.createDirectories(model);
        Files.createDirectories(svc);
        Files.writeString(model.resolve("Order.java"), ORDER_ENTITY);
        Files.writeString(model.resolve("OrderItem.java"), ORDER_ITEM_ENTITY);
        Path serviceFile = svc.resolve("OrderService.java");
        Files.writeString(serviceFile, service);
        return serviceFile;
    }

    @Test
    void injectsBackReferenceBeforeSave(@TempDir Path root) throws IOException {
        Path service = scaffold(root, BUGGY_SERVICE);

        boolean changed = JpaBidirectionalSavePatcher.fix(root);

        assertThat(changed).isTrue();
        String out = Files.readString(service);
        // the back-reference is set on every child, and BEFORE the save
        assertThat(out).contains("orderItems.forEach(__child -> __child.setOrder(order));");
        assertThat(out.indexOf("setOrder(order)")).isLessThan(out.indexOf("orderRepository.save(order)"));
    }

    @Test
    void isIdempotent(@TempDir Path root) throws IOException {
        Path service = scaffold(root, BUGGY_SERVICE);

        JpaBidirectionalSavePatcher.fix(root);
        String once = Files.readString(service);
        JpaBidirectionalSavePatcher.fix(root);
        String twice = Files.readString(service);

        assertThat(twice).isEqualTo(once);
        assertThat(twice.split("forEach", -1)).as("exactly one injection").hasSize(2);
    }

    @Test
    void leavesAlreadyCorrectCodeUntouched(@TempDir Path root) throws IOException {
        String correct = """
                package com.x.service;
                public class OrderService {
                    public Order createOrder(java.util.List<OrderItem> orderItems) {
                        Order order = new Order();
                        order.setItems(orderItems);
                        orderItems.forEach(i -> i.setOrder(order));
                        return orderRepository.save(order);
                    }
                }
                """;
        Path service = scaffold(root, correct);

        boolean changed = JpaBidirectionalSavePatcher.fix(root);

        assertThat(changed).isFalse();
        assertThat(Files.readString(service)).isEqualTo(correct);
    }

    @Test
    void ignoresUnidirectionalOneToMany(@TempDir Path root) throws IOException {
        // no mappedBy => owning side is a join column on the parent, not this bug
        String uni = ORDER_ENTITY.replace("mappedBy = \"order\", ", "");
        Path model = root.resolve("com/x/model");
        Path svc = root.resolve("com/x/service");
        Files.createDirectories(model);
        Files.createDirectories(svc);
        Files.writeString(model.resolve("Order.java"), uni);
        Files.writeString(model.resolve("OrderItem.java"), ORDER_ITEM_ENTITY);
        Path service = svc.resolve("OrderService.java");
        Files.writeString(service, BUGGY_SERVICE);

        boolean changed = JpaBidirectionalSavePatcher.fix(root);

        assertThat(changed).isFalse();
    }
}
