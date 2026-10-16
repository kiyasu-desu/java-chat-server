package com.kiyasu.chat.service.impl;

import lombok.extern.slf4j.Slf4j;
import com.kiyasu.chat.entity.Message;
import com.kiyasu.chat.service.MessageService;

@Slf4j
public class MessageServiceImpl implements MessageService {
    @Override
    public Message splitMessage(String str) {
        String[] split = str.split(",", 3);
        if (split.length < 3) {
            log.warn("消息格式错误: {}", str);
            return null;
        }
        Message message = Message.builder()
                .srcId(split[0])
                .dstId(split[1])
                .msg(split[2])
                .build();
        log.info("消息解析成功: {}", message);
        return message;
    }
}
