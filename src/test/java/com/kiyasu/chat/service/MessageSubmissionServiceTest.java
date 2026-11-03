package com.kiyasu.chat.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.kiyasu.chat.common.Constant;
import com.kiyasu.chat.entity.Message;
import com.kiyasu.chat.entity.Result;
import com.kiyasu.chat.util.OnlineRegistryUtil;
import org.junit.Test;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.util.concurrent.*;

import static org.junit.Assert.*;

public class MessageSubmissionServiceTest {

    /**
     * 消息源ID 不匹配当前登录用户时应返回失败
     */
    @Test
    public void mismatchedSenderShouldLogClaimedAndCurrentUserIds() {

        LinkedBlockingQueue<Message> messages = new LinkedBlockingQueue<>(1);
        OnlineRegistryUtil registry = new OnlineRegistryUtil();
        MessageSubmissionService messageSubmissionService = new MessageSubmissionService(messages, registry);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();

        Logger logger = (Logger) LoggerFactory.getLogger(MessageSubmissionService.class);

        appender.start();
        logger.addAppender(appender);

        try {

            messageSubmissionService.submit("Alice", new Message("Marry", "Bob", "hello"));

            assertEquals(1, appender.list.size());
            ILoggingEvent event = appender.list.getFirst();

            assertEquals(Level.WARN, event.getLevel());
            assertEquals(
                    "发送消息失败: 消息源ID Marry 不匹配当前登录用户 Alice",
                    event.getFormattedMessage()
            );
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    /**
     * 测试目标：队列已满时, submit() 应及时返回“服务器繁忙”, 并保留原先排队的消息。
     */
    @Test
    public void fullQueueShouldRejectNewMessageAndKeepQueuedMessage() throws Exception {

        LinkedBlockingQueue<Message> messages = new LinkedBlockingQueue<>(1);
        OnlineRegistryUtil registry = new OnlineRegistryUtil();

        MessageSubmissionService messageSubmissionService = new MessageSubmissionService(messages, registry);

        ExecutorService pool = Executors.newSingleThreadExecutor();

        try (Socket socket = new MemorySocket();
             ClientService client = new ClientService(socket, new LinkedBlockingQueue<>(), registry)) {

            assertTrue(registry.register("Bob", client));

            Message oldMsg = new Message("Alice", "Bob", "hello");
            Message newMsg = new Message("Alice", "Bob", "hi");

            assertTrue(messages.add(oldMsg));

            Future<Result> resultFuture =
                    pool.submit(() -> messageSubmissionService.submit("Alice", newMsg));

            try {

                Result testResult = resultFuture.get(2, TimeUnit.SECONDS);
                Result failResult = new Result(Constant.ResultType.FAIL, "服务器繁忙");

                assertEquals(1, messages.size());
                assertSame(oldMsg, messages.peek());
                assertEquals(failResult, testResult);

            } finally {
                resultFuture.cancel(true);
            }

        } finally {
            pool.shutdownNow();
        }

        assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS));

    }

    /**
     * 正常消息发送
     */
    @Test
    public void availableQueueShouldAcceptMessage() throws Exception {

        LinkedBlockingQueue<Message> messages = new LinkedBlockingQueue<>(1);
        OnlineRegistryUtil registry = new OnlineRegistryUtil();

        MessageSubmissionService messageSubmissionService = new MessageSubmissionService(messages, registry);

        try (Socket socket = new MemorySocket();
             ClientService client = new ClientService(socket, new LinkedBlockingQueue<>(), registry)) {

            assertTrue(registry.register("Bob", client));

            Message msg = new Message("Alice", "Bob", "hello");

            Result alice = messageSubmissionService.submit("Alice", msg);
            Result right = new Result(Constant.ResultType.SUCCESS, "消息已加入发送队列");

            assertEquals(right, alice);
            assertEquals(1, messages.size());
            assertSame(msg, messages.peek());

        }

    }

    /**
     * 发送者身份不匹配应拒绝入队
     */
    @Test
    public void mismatchedSenderShouldBeRejected() {

        LinkedBlockingQueue<Message> messages = new LinkedBlockingQueue<>(1);
        OnlineRegistryUtil registry = new OnlineRegistryUtil();
        MessageSubmissionService messageSubmissionService = new MessageSubmissionService(messages, registry);

        Message msg = new Message("Marry", "Bob", "hello");

        Result result = messageSubmissionService.submit("Alice", msg);

        assertEquals(new Result(Constant.ResultType.FAIL, "消息源ID不匹配当前登录用户"), result);
        assertTrue(messages.isEmpty());

    }

    /**
     * 接收者离线应拒绝入队
     */
    @Test
    public void offlineRecipientShouldBeRejected() {

        LinkedBlockingQueue<Message> messages = new LinkedBlockingQueue<>(1);
        OnlineRegistryUtil registry = new OnlineRegistryUtil();
        MessageSubmissionService messageSubmissionService = new MessageSubmissionService(messages, registry);

        Message msg = new Message("Alice", "Bob", "hello");

        Result result = messageSubmissionService.submit("Alice", msg);

        assertEquals(new Result(Constant.ResultType.FAIL, "接收者离线"), result);
        assertTrue(messages.isEmpty());

    }

    /**
     * 用户未登录时应请求失败
     */
    @Test
    public void requestShouldFailWhenUserIsNotLoggedIn() throws Exception {

        BlockingQueue<Message> messages = new LinkedBlockingQueue<>(1);
        OnlineRegistryUtil registry = new OnlineRegistryUtil();
        MessageSubmissionService messageSubmissionService = new MessageSubmissionService(messages, registry);

        Result result1 = messageSubmissionService.submit(
                null, Message.broadcast("Alice", "Hello, World!"));

        assertEquals(new Result(Constant.ResultType.FAIL, "用户未登录"), result1);
        assertTrue(messages.isEmpty());

        Result result2 = messageSubmissionService.submit(
                null, Message.broadcast(null, "Hello, World!"));

        assertEquals(new Result(Constant.ResultType.FAIL, "用户未登录"), result2);
        assertTrue(messages.isEmpty());

    }

    /**
     * 正常群发消息
     */
    @Test
    public void availableQueueShouldAcceptExactlyOneBroadcastMessage() {

        BlockingQueue<Message> messages = new LinkedBlockingQueue<>(1);
        OnlineRegistryUtil registry = new OnlineRegistryUtil();
        MessageSubmissionService messageSubmissionService = new MessageSubmissionService(messages, registry);

        Message msg = Message.broadcast("Alice", "Hello, World!");
        Result result = messageSubmissionService.submit("Alice", msg);

        assertTrue(msg.isBroadcast());
        assertEquals("Alice", msg.getSrcId());
        assertNull(msg.getDstId());
        assertEquals("Hello, World!", msg.getMsg());

        Result right = new Result(Constant.ResultType.SUCCESS, "消息已加入发送队列");
        assertEquals(right, result);
        assertEquals(1, messages.size());
        assertNotNull(messages.peek());
        assertSame(msg, messages.peek());

    }

    /**
     * 群发消息源 ID 与会话 ID 不符时拒绝入队
     */
    @Test
    public void enqueueingShouldRejectedWhenBroadcastMessageSourceIDNotMatchSessionID() throws Exception {

        BlockingQueue<Message> messages = new LinkedBlockingQueue<>(1);
        OnlineRegistryUtil registry = new OnlineRegistryUtil();
        MessageSubmissionService messageSubmissionService = new MessageSubmissionService(messages, registry);

        Message msg = Message.broadcast("Alice", "Hello, World!");
        Result result = messageSubmissionService.submit("Mary", msg);

        assertEquals(new Result(Constant.ResultType.FAIL, "消息源ID不匹配当前登录用户"), result);
        assertTrue(messages.isEmpty());

    }

    /**
     * 群发满载测试
     */
    @Test
    public void fullQueueShouldRejectNewMessageAndKeepQueuedMessageVerBroadcast() throws Exception {

        LinkedBlockingQueue<Message> messages = new LinkedBlockingQueue<>(1);
        OnlineRegistryUtil registry = new OnlineRegistryUtil();

        MessageSubmissionService messageSubmissionService = new MessageSubmissionService(messages, registry);

        ExecutorService pool = Executors.newSingleThreadExecutor();

        try (Socket socket = new MemorySocket();
             ClientService client = new ClientService(socket, new LinkedBlockingQueue<>(), registry)) {

            assertTrue(registry.register("Bob", client));

            Message oldMsg = Message.broadcast("Alice", "hello");
            Message newMsg = Message.broadcast("Alice", "hi");

            assertTrue(messages.add(oldMsg));

            Future<Result> resultFuture =
                    pool.submit(() -> messageSubmissionService.submit("Alice", newMsg));

            try {

                Result testResult = resultFuture.get(2, TimeUnit.SECONDS);
                Result failResult = new Result(Constant.ResultType.FAIL, "服务器繁忙");

                assertEquals(1, messages.size());
                assertSame(oldMsg, messages.peek());
                assertEquals(failResult, testResult);

            } finally {
                resultFuture.cancel(true);
            }

        } finally {
            pool.shutdownNow();
        }

        assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS));

    }

    private static class MemorySocket extends Socket {
        private final OutputStream outputStream = new ByteArrayOutputStream();

        @Override
        public void close() throws IOException {
            outputStream.close();
            super.close();
        }

        @Override
        public OutputStream getOutputStream() {
            return outputStream;
        }
    }

}
