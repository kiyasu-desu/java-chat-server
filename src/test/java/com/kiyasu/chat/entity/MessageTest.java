package com.kiyasu.chat.entity;

import org.junit.Test;

import static org.junit.Assert.*;

public class MessageTest {

    /**
     * 测试: 三参数构造器应该产生单聊消息
     */
    @Test
    public void threeParameterConstructorShouldProduceASingleChatMessage() {
        Message message = new Message("Bob", "Alice", "Hello!");
        assertNotNull(message);
        assertEquals("Bob", message.getSrcId());
        assertEquals("Alice", message.getDstId());
        assertEquals("Hello!", message.getMsg());
        assertFalse(message.isBroadcast());
    }

    /**
     * 测试: 群发含逗号正文消息
     */
    @Test
    public void sendGroupMessagesWithCommasInBody() {
        Message message = Message.broadcast("Bob", "Hello,world!");
        assertNotNull(message);
        assertEquals("Bob", message.getSrcId());
        assertNull(message.getDstId());
        assertEquals("Hello,world!", message.getMsg());
        assertTrue(message.isBroadcast());
    }

    /**
     * 测试: 群发空正文消息
     */
    @Test
    public void SendGroupMessagesWithAnEmptyBody() {
        Message message = Message.broadcast("Bob", "");
        assertNotNull(message);
        assertEquals("Bob", message.getSrcId());
        assertNull(message.getDstId());
        assertEquals("", message.getMsg());
        assertTrue(message.isBroadcast());
    }
}
