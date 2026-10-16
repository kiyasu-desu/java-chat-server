package com.kiyasu.chat.service.impl;


import com.kiyasu.chat.common.Constant;
import com.kiyasu.chat.entity.Order;
import com.kiyasu.chat.service.OrderService;

public class OrderServiceImpl implements OrderService {

    @Override
    public Order splitOrder(String order) {
        if (order == null || order.isEmpty()) {
            return null;
        }

        String[] orderSplit = order.split(",", 2);
        String orderId = orderSplit[0].trim();
        if (orderId.isEmpty()) {
            return null;
        }

        String content = orderSplit.length > 1 ? orderSplit[1] : "";

        try {
            Constant.UserOrder orderType = null;

            //匹配命令码
            for (Constant.UserOrder type : Constant.UserOrder.values()) {
                if (type.getOrderNo().equals(orderId)) {
                    orderType = type;
                    break;
                }
            }
            Order order1 = new Order();
            order1.setOrder(orderType);
            order1.setContent(content);
            return order1;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
