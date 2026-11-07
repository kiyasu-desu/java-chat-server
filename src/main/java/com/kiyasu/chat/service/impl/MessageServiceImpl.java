package com.kiyasu.chat.service.impl;

import com.kiyasu.chat.entity.Message;
import com.kiyasu.chat.service.MessageService;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class MessageServiceImpl implements MessageService {
    @Override
    public Message splitMessage(String str) {
        if (null == str) {
            log.warn("消息信息为空");
            return null;
        }
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
