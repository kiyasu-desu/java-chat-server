package com.kiyasu.chat.service;

import com.kiyasu.chat.common.Constant;
import com.kiyasu.chat.entity.Message;
import com.kiyasu.chat.entity.Result;
import com.kiyasu.chat.util.OnlineRegistryUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.BlockingQueue;

@Slf4j
public class MessageSubmissionService {

    private final BlockingQueue<Message> messageBlockingQueue;

    private final OnlineRegistryUtil messageRegistry;

    public MessageSubmissionService(BlockingQueue<Message> messages, OnlineRegistryUtil registry) {
        messageBlockingQueue = messages;
        messageRegistry = registry;
    }

    public Result submit(String currentUserId, Message message) {
        Result result = new Result();

        if (!message.getSrcId().equals(currentUserId)) {
            log.warn("发送消息失败: 消息源ID {} 不匹配当前登录用户 {}", message.getSrcId(), currentUserId);
            result.setResult(Constant.ResultType.FAIL);
            result.setInfo("消息源ID不匹配当前登录用户");
            return result;
        }

        try {
            if (messageRegistry.isOnline(message.getDstId())) {
                if (messageBlockingQueue.offer(message)) {
                    result.setResult(Constant.ResultType.SUCCESS);
                    result.setInfo("消息已加入发送队列");
                    log.info("{} 发送消息给 {}，消息已加入发送队列", message.getSrcId(), message.getDstId());
                } else {
                    result.setResult(Constant.ResultType.FAIL);
                    result.setInfo("服务器繁忙");
                }
            } else {
                log.warn("{} 发送消息失败: 接收者 {} 离线", message.getSrcId(), message.getDstId());
                result.setResult(Constant.ResultType.FAIL);
                result.setInfo("接收者离线");
            }
            return result;

        } catch (Exception e) {
            log.error("{} 发送消息失败: {}", message.getSrcId(), e.getMessage());
            result.setResult(Constant.ResultType.FAIL);
            result.setInfo(e.getMessage());
            return result;
        }
    }

}
