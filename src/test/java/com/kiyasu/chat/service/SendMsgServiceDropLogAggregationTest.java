package com.kiyasu.chat.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.kiyasu.chat.entity.Message;
import com.kiyasu.chat.util.OnlineRegistryUtil;
import org.junit.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class SendMsgServiceDropLogAggregationTest {

    @Test(timeout = 5000)
    public void dropLogsShouldAggregateByReceiverAndReportTailOnRecovery() throws Exception {

        LinkedBlockingQueue<Message> shared = new LinkedBlockingQueue<>(1024);
        OnlineRegistryUtil registry = new OnlineRegistryUtil();
        SendMsgService sendMsgService = new SendMsgService(shared, registry);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        List<ILoggingEvent> dropEvents;
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        Logger logger = (Logger) LoggerFactory.getLogger(SendMsgService.class);

        appender.start();
        logger.addAppender(appender);

        try (SendMsgServiceTest.BlockingSocket bobSocket = new SendMsgServiceTest.BlockingSocket();
             ClientService bobClient = new ClientService(bobSocket, new LinkedBlockingQueue<>(), registry)) {

            executor.submit(sendMsgService);
            assertTrue(registry.register("Bob", bobClient));

            assertTrue(bobClient.send("first"));
            assertTrue(bobSocket.awaitWriteEntered(1, TimeUnit.SECONDS));

            while (bobClient.send("fill")) {
            }

            for (int i = 0; i <= 1000; i++) {
                shared.put(new Message("Alice", "Bob", "m" + i));
            }

            assertTrue(SendMsgServiceTest.awaitUntil(
                    () -> dropEvents(appender).size() >= 2, 2000));
            dropEvents = dropEvents(appender);
            assertEquals(
                    "接收者 Bob 出站队列满载, 开始丢弃消息, 此后每 1000 条汇总一次",
                    dropEvents.getFirst().getFormattedMessage());
            assertEquals(
                    "接收者 Bob 丢弃持续, 本次新增 1000 条, 累计 1000 条",
                    dropEvents.get(1).getFormattedMessage());

            try (SendMsgServiceTest.RecordingSocket barrierSocket = new SendMsgServiceTest.RecordingSocket();
                 ClientService barrierClient = new ClientService(barrierSocket, new LinkedBlockingQueue<>(), registry)) {
                assertTrue(registry.register("Barrier", barrierClient));
                shared.put(new Message("Alice", "Barrier", "processed"));
                assertTrue(barrierSocket.awaitMessage(1, TimeUnit.SECONDS));
                assertTrue(registry.unregister("Barrier", barrierClient));
            }
            bobSocket.release();
            assertTrue(SendMsgServiceTest.awaitUntil(
                    () -> bobSocket.getWrittenText().contains("fill\n"), 2000));
            shared.put(new Message("Alice", "Bob", "after"));
            assertTrue(SendMsgServiceTest.awaitUntil(
                    () -> dropEvents(appender).size() >= 3, 2000));
            dropEvents = dropEvents(appender);
            assertEquals("接收者 Bob 恢复/离线, 未汇总尾数 1 条, 本次饱和共丢弃 1001 条",
                    dropEvents.get(2).getFormattedMessage());

        } finally {
            logger.detachAppender(appender);
            appender.stop();
            sendMsgService.shutdown();
            executor.shutdownNow();
        }

        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));

    }

    @Test(timeout = 5000)
    public void dropLogsShouldReportTailOnForwarderExit() throws Exception {

        LinkedBlockingQueue<Message> shared = new LinkedBlockingQueue<>(1024);
        OnlineRegistryUtil registry = new OnlineRegistryUtil();
        SendMsgService sendMsgService = new SendMsgService(shared, registry);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        List<ILoggingEvent> dropEvents;

        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        Logger logger = (Logger) LoggerFactory.getLogger(SendMsgService.class);

        appender.start();
        logger.addAppender(appender);

        try (SendMsgServiceTest.BlockingSocket bobSocket = new SendMsgServiceTest.BlockingSocket();
             ClientService bobClient = new ClientService(bobSocket, new LinkedBlockingQueue<>(), registry)) {

            executor.submit(sendMsgService);
            assertTrue(registry.register("Bob", bobClient));

            assertTrue(bobClient.send("first"));
            assertTrue(bobSocket.awaitWriteEntered(1, TimeUnit.SECONDS));

            while (bobClient.send("fill")) {
            }

            for (int i = 0; i < 1000; i++) {
                shared.put(new Message("Alice", "Bob", "m" + i));
            }

            assertTrue(SendMsgServiceTest.awaitUntil(
                    () -> dropEvents(appender).size() >= 2, 2000));
            dropEvents = dropEvents(appender);
            assertEquals(
                    "接收者 Bob 出站队列满载, 开始丢弃消息, 此后每 1000 条汇总一次",
                    dropEvents.getFirst().getFormattedMessage());
            assertEquals(
                    "接收者 Bob 丢弃持续, 本次新增 1000 条, 累计 1000 条",
                    dropEvents.get(1).getFormattedMessage());

            for (int i = 0; i < 3; i++) {
                shared.put(new Message("Alice", "Bob", "after" + i));
            }

            assertTrue(SendMsgServiceTest.awaitUntil(shared::isEmpty, 2000));
            executor.shutdownNow();

            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
            dropEvents = dropEvents(appender);
            assertEquals("接收者 Bob 恢复/离线, 未汇总尾数 3 条, 本次饱和共丢弃 1003 条",
                    dropEvents.get(2).getFormattedMessage());

        } finally {
            logger.detachAppender(appender);
            appender.stop();
            sendMsgService.shutdown();
            executor.shutdownNow();
        }

        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));

    }

    @Test(timeout = 5000)
    public void dropLogsShouldReportTailWhenReceiverGoesOffline() throws Exception {

        LinkedBlockingQueue<Message> shared = new LinkedBlockingQueue<>(1024);
        OnlineRegistryUtil registry = new OnlineRegistryUtil();
        SendMsgService sendMsgService = new SendMsgService(shared, registry);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        List<ILoggingEvent> dropEvents;

        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        Logger logger = (Logger) LoggerFactory.getLogger(SendMsgService.class);

        appender.start();
        logger.addAppender(appender);

        try (SendMsgServiceTest.BlockingSocket bobSocket = new SendMsgServiceTest.BlockingSocket();
             ClientService bobClient = new ClientService(bobSocket, new LinkedBlockingQueue<>(), registry)) {

            executor.submit(sendMsgService);
            assertTrue(registry.register("Bob", bobClient));

            assertTrue(bobClient.send("first"));
            assertTrue(bobSocket.awaitWriteEntered(1, TimeUnit.SECONDS));

            while (bobClient.send("fill")) {
            }

            for (int i = 0; i < 1000; i++) {
                shared.put(new Message("Alice", "Bob", "m" + i));
            }

            assertTrue(SendMsgServiceTest.awaitUntil(
                    () -> dropEvents(appender).size() >= 2, 2000));
            dropEvents = dropEvents(appender);
            assertEquals(
                    "接收者 Bob 出站队列满载, 开始丢弃消息, 此后每 1000 条汇总一次",
                    dropEvents.getFirst().getFormattedMessage());
            assertEquals(
                    "接收者 Bob 丢弃持续, 本次新增 1000 条, 累计 1000 条",
                    dropEvents.get(1).getFormattedMessage());

            for (int i = 0; i < 3; i++) {
                shared.put(new Message("Alice", "Bob", "after" + i));
            }
            assertTrue(SendMsgServiceTest.awaitUntil(shared::isEmpty, 2000));

            assertTrue(registry.unregister("Bob", bobClient));
            shared.put(new Message("Alice", "Bob", "after"));
            assertTrue(SendMsgServiceTest.awaitUntil(
                    () -> dropEvents(appender).size() >= 4, 2000));

            dropEvents = dropEvents(appender);
            assertEquals("目标用户 Bob 不在线, 无法发送消息",
                    dropEvents.get(2).getFormattedMessage());
            assertEquals("接收者 Bob 恢复/离线, 未汇总尾数 3 条, 本次饱和共丢弃 1003 条",
                    dropEvents.get(3).getFormattedMessage());

        } finally {
            logger.detachAppender(appender);
            appender.stop();
            sendMsgService.shutdown();
            executor.shutdownNow();
        }

        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));

    }

    private static List<ILoggingEvent> dropEvents(ListAppender<ILoggingEvent> appender) {
        return appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .filter(e -> e.getLoggerName().equals(SendMsgService.class.getName()))
                .toList();
    }

}
