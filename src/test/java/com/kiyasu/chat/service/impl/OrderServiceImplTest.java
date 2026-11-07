package com.kiyasu.chat.service.impl;

import com.kiyasu.chat.common.Constant;
import com.kiyasu.chat.entity.Order;
import com.kiyasu.chat.service.OrderService;
import org.junit.Test;

import static org.junit.Assert.*;

public class OrderServiceImplTest {

    private final OrderService orderService = new OrderServiceImpl();

    /**
     * 测试: 正常注册命令
     */
    @Test
    public void registerOrderShouldParseTypeAndContent() {

        String string = "001,Alice,pwd";
        Order order = orderService.splitOrder(string);

        assertSame(Constant.UserOrder.REGISTER_REQ, order.getOrder());
        assertEquals("Alice,pwd", order.getContent());

    }

    /**
     * 测试: 只有命令码，没有逗号
     */
    @Test
    public void orderWithoutCommaShouldHaveNullContent() {

        String string = "004";
        Order order = orderService.splitOrder(string);

        assertSame(Constant.UserOrder.FIND_REQ, order.getOrder());
        assertNull(order.getContent());

    }

    /**
     * 测试: 只有命令码和逗号
     */
    @Test
    public void orderOnlyWithCommaShouldHaveEmptyContent() {

        String string = "006,";
        Order order = orderService.splitOrder(string);

        assertSame(Constant.UserOrder.GROUP_MSG_REQ, order.getOrder());
        assertEquals("", order.getContent());

    }

    /**
     * 测试: 第一个逗号之后的内容完整保留
     */
    @Test
    public void sendOrderShouldPreserveContentAfterFirstComma() {

        String string = "005,Alice,Bob,hello,world";
        Order order = orderService.splitOrder(string);

        assertSame(Constant.UserOrder.SEND_MSG_REQ, order.getOrder());
        assertEquals("Alice,Bob,hello,world", order.getContent());

    }

    /**
     * 测试: 只 trim 命令码，不 trim 内容
     */
    @Test
    public void onlyOrderIdShouldBeTrimmed() {

        String string = " 004 , test ";
        Order order = orderService.splitOrder(string);

        assertSame(Constant.UserOrder.FIND_REQ, order.getOrder());
        assertEquals(" test ", order.getContent());

    }

    /**
     * 测试: 缺少有效命令码
     */
    @Test
    public void missingOrderIdShouldReturnNull() {

        assertNull(orderService.splitOrder(null));
        assertNull(orderService.splitOrder(""));
        assertNull(orderService.splitOrder("       "));
        assertNull(orderService.splitOrder(",test"));

    }

    /**
     * 测试: 未知命令码
     */
    @Test
    public void unknownOrderShouldKeepContentWithNullType() {

        String string = "10086, test";
        Order order = orderService.splitOrder(string);

        assertNotNull(order);
        assertNull(order.getOrder());
        assertEquals(" test", order.getContent());

    }

    /**
     * 测试: 群发命令
     */
    @Test
    public void broadcastOrderShouldParseTypeAndContent() {
        String string = "006,hello,world";
        Order order = orderService.splitOrder(string);

        assertNotNull(order);
        assertNotNull(order.getOrder());
        assertEquals("006", order.getOrder().getOrderNo());
        assertEquals(Constant.UserOrder.GROUP_MSG_REQ, order.getOrder());
        assertEquals("hello,world", order.getContent());
    }


}
