package u3123004589.service.impl;


import u3123004589.common.Constant;
import u3123004589.entity.Order;
import u3123004589.service.OrderService;

import java.util.Arrays;

public class OrderServiceImpl implements OrderService {

    @Override
    public Order splitOrder(String order) {
        String[] orderSplit = order.split("\\s+", 2);
        String orderId = orderSplit[0];
        String content = orderSplit.length > 1 ? orderSplit[1] : "";

        Order order1 = new Order();
        try {
            Constant.OrderType orderType = Constant.OrderType.valueOf(orderId);
            order1.setOrder(orderType);
            order1.setContent(content);
            return order1;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
