package com.kiyasu.chat.controller;

import com.kiyasu.chat.entity.Message;
import com.kiyasu.chat.service.ClientService;
import com.kiyasu.chat.service.SendMsgService;
import com.kiyasu.chat.util.OnlineRegistryUtil;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.*;

@Slf4j
public class Server {

    public static final int MAX_CLIENTS = 30;       // 会话上限常量

    public static void main(String[] args) {
        BlockingQueue<Message> sendQueue = new LinkedBlockingQueue<>(1024);
        OnlineRegistryUtil registry = new OnlineRegistryUtil();

        SendMsgService sender = new SendMsgService(sendQueue, registry);
        Thread senderThread = new Thread(sender, "SendMsgService");
        senderThread.start();

        try (ServerSocket server = new ServerSocket(23456);
             ExecutorService pool = newClientPool(MAX_CLIENTS)) {
            System.out.println("server listening: ");
            while (true) {
                Socket client = server.accept();
                dispatchClient(client, pool, sendQueue, registry);
            }
        } catch (IOException e) {
            log.error("服务器发生错误", e);
        } finally {
            sender.shutdown();
            senderThread.interrupt();
            try {
                senderThread.join(1000);
            } catch (InterruptedException e) {
                log.error("等待发送线程结束时发生错误", e);
            }
        }
    }

    /**
     * 线程池工厂方法
     */
    public static ExecutorService newClientPool(int maxClients) {
        return new ThreadPoolExecutor(
                maxClients,
                maxClients,
                0L,
                TimeUnit.MILLISECONDS,
                new SynchronousQueue<>(),
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    /**
     * 单连接派发
     */
    public static void dispatchClient(Socket client,
                                      ExecutorService pool,
                                      BlockingQueue<Message> queue,
                                      OnlineRegistryUtil registry) {

        try {
            pool.execute(() -> {
                try(ClientService service = new ClientService(client, queue, registry)) {
                    service.run();
                } catch (IOException e) {
                    log.error("初始化客户端 {} 会话失败: {}", client.getRemoteSocketAddress(), e.getMessage());
                    closeClientQuietly(client);
                }
            });
        } catch (RejectedExecutionException e) {
            log.warn("连接数已达上限, 拒绝客户端 {}", client.getRemoteSocketAddress());
            closeClientQuietly(client);
        }

    }

    private static void closeClientQuietly(Socket client) {
        try {
            client.close();
        } catch (IOException e) {
            log.error("关闭客户端 {} 连接时发生错误: {}", client.getRemoteSocketAddress(), e.getMessage());
        }
    }
}
