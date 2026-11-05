package com.kiyasu.chat.service.impl;

import com.kiyasu.chat.entity.Message;
import com.kiyasu.chat.service.MessageService;
import org.junit.Test;

import static org.junit.Assert.*;

public class MessageServiceImplTest {

    private final MessageService messageService = new MessageServiceImpl();

    /**
     * 测试: 正常消息解析
     */
    @Test
    public void normalMessageShouldParseAllFields() {

        String string = "Alice,Bob,hello";
        Message message = messageService.splitMessage(string);

        assertEquals("Alice", message.getSrcId());
        assertEquals("Bob", message.getDstId());
        assertEquals("hello", message.getMsg());

    }

    /**
     * 测试: 正文中的额外逗号全部保留
     */
    @Test
    public void messageBodyShouldPreserveAdditionalCommas() {

        String string = "Alice,Bob,hello,world,this,is,a,test";
        Message message = messageService.splitMessage(string);

        assertEquals("Alice", message.getSrcId());
        assertEquals("Bob", message.getDstId());
        assertEquals("hello,world,this,is,a,test", message.getMsg());

    }

    /**
     * 测试: 空正文是合法的三段结构
     */
    @Test
    public void emptyMessageBodyShouldBeAccepted() {

        String string = "Alice,Bob,";
        Message message = messageService.splitMessage(string);

        assertNotNull(message);
        assertEquals("Alice", message.getSrcId());
        assertEquals("Bob", message.getDstId());
        assertEquals("", message.getMsg());

    }

    /**
     * 测试: 字段不足返回 null
     */
    @Test
    public void incompleteMessageShouldReturnNull() {

        assertNull(messageService.splitMessage(""));
        assertNull(messageService.splitMessage("Alice"));
        assertNull(messageService.splitMessage("Alice,Bob"));

    }

    /**
     * 测试: 生产 Builder 路径默认单聊
     */
    @Test
    public void normalMessageBroadcastShouldBeFalse() {

        String string = "Alice,Bob,hello";
        Message message = messageService.splitMessage(string);

        assertFalse(message.isBroadcast());

    }


}
