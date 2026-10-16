package com.kiyasu.chat.service;


import com.kiyasu.chat.entity.Order;

public interface OrderService {
    Order splitOrder(String order);
}
