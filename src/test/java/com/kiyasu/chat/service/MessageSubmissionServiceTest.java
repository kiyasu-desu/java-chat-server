package com.kiyasu.chat.service;

import com.kiyasu.chat.common.Constant;
import com.kiyasu.chat.entity.Message;
import com.kiyasu.chat.entity.Result;
import com.kiyasu.chat.util.OnlineRegistryUtil;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.util.concurrent.*;

import static org.junit.Assert.*;

public class MessageSubmissionServiceTest {

    /**
     * 测试目标：队列已满时，submit() 应及时返回“服务器繁忙”，并保留原先排队的消息。
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
