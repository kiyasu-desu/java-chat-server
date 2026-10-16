package com.kiyasu.chat.controller;

import lombok.extern.slf4j.Slf4j;
import com.kiyasu.chat.entity.Message;
import com.kiyasu.chat.service.ClientService;
import com.kiyasu.chat.service.SendMsgService;
import com.kiyasu.chat.util.OnlineRegistryUtil;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;

@Slf4j
public class Server {
    public static void main(String[] args) {
        BlockingQueue<Message> sendQueue = new LinkedBlockingQueue<>();
        OnlineRegistryUtil registry = new OnlineRegistryUtil();

        SendMsgService sender = new SendMsgService(sendQueue, registry);
        Thread senderThread = new Thread(sender, "SendMsgService");
        senderThread.start();

        try (ServerSocket server = new ServerSocket(23456);
             ExecutorService pool = Executors.newFixedThreadPool(30)) {
            System.out.println("server listening： ");
            while (true) {
                Socket client = server.accept();
                pool.submit(new ClientService(client, sendQueue, registry));
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
}
