package com.kiyasu.chat.service;

import lombok.extern.slf4j.Slf4j;
import com.kiyasu.chat.common.Constant;
import com.kiyasu.chat.entity.Message;
import com.kiyasu.chat.util.OnlineRegistryUtil;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;

@Slf4j
public class SendMsgService implements Runnable {
    private final BlockingQueue<Message> queue;
    private final OnlineRegistryUtil registry;
    private volatile boolean running = true;
    private final Map<String, long[]> dropStats = new HashMap<>();

    private static final long SUMMARY_INTERVAL = 1000;

    public SendMsgService(BlockingQueue<Message> queue, OnlineRegistryUtil registry) {
        this.queue = queue;
        this.registry = registry;
    }

    public void shutdown() {
        running = false;
    }

    @Override
    public void run() {
        while (running) {
            try {
                Message message = queue.take();

                ClientService dst = registry.getClient(message.getDstId());
                if (dst != null) {
                    String toSend = Constant.ServerOrder.MSG_RES.getOrderNo() + ","
                            + message.getSrcId() + ","
                            + message.getDstId() + ","
                            + message.getMsg();

                    if (!dst.send(toSend)) {
                        recordDrop(message.getDstId());
                    } else {
                        reportTail(message.getDstId());
                    }
                } else {
                    log.warn("目标用户 {} 不在线, 无法发送消息", message.getDstId());
                    reportTail(message.getDstId());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.info("发送消息线程收到中断, 退出");
                break;
            }
        }
        for (String dstId : List.copyOf(dropStats.keySet())) {
            reportTail(dstId);
        }

    }

    private void recordDrop(String dstId) {
        long[] stat = dropStats.computeIfAbsent(dstId, _ -> new long[2]);
        stat[0]++;
        stat[1]++;

        if (stat[0] == 1) {
            log.warn("接收者 {} 出站队列满载, 开始丢弃消息, 此后每 {} 条汇总一次", dstId, SUMMARY_INTERVAL);
        } else if (stat[1] >= SUMMARY_INTERVAL) {
            log.warn("接收者 {} 丢弃持续, 本次新增 {} 条, 累计 {} 条", dstId, stat[1], stat[0]);
            stat[1] = 0;
        }
    }

    private void reportTail(String dstId) {
        long[] stat = dropStats.remove(dstId);
        if (stat != null && stat[1] > 0) {
            log.warn("接收者 {} 恢复/离线, 未汇总尾数 {} 条, 本次饱和共丢弃 {} 条", dstId, stat[1], stat[0]);
        }
    }
}
