package u3123004589.service;

import lombok.extern.slf4j.Slf4j;
import u3123004589.common.Constant;
import u3123004589.entity.Message;
import u3123004589.util.OnlineRegistryUtil;

import java.util.concurrent.BlockingQueue;

@Slf4j
public class SendMsgService implements Runnable {
    private final BlockingQueue<Message> queue;
    private final OnlineRegistryUtil registry;
    private volatile boolean running = true;

    public SendMsgService(BlockingQueue<Message> queue, OnlineRegistryUtil registry) {
        this.queue = queue;
        this.registry = registry;
    }

    public void shutdown() {
        running = false;
        Thread.currentThread().interrupt();
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
                        log.error("目标在线但发送失败，发送者: {}, 接收者: {}", message.getSrcId(), message.getDstId());
                    }
                } else {
                    log.warn("目标用户 {} 不在线，无法发送消息", message.getDstId());
                }
            } catch (InterruptedException e) {
                log.error("发送消息线程被中断", e);
                Thread.currentThread().interrupt();
            }
        }

    }
}
