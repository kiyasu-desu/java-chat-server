package com.kiyasu.chat.service;

import com.kiyasu.chat.common.Constant;
import com.kiyasu.chat.entity.Message;
import com.kiyasu.chat.util.OnlineRegistryUtil;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.*;

public class SendMsgServiceTest {

    @Test
    public void closeShouldDrainQueuedResponseAndStopWriter() throws IOException, InterruptedException {

        long before = countWriterThreads();

        try (RecordingSocket socket = new RecordingSocket();
             ClientService client = new ClientService(socket, new LinkedBlockingQueue<>(), new OnlineRegistryUtil())) {
            assertTrue(client.send("drain-1"));
            assertTrue(client.send("drain-2"));
            client.close();

            assertEquals(List.of("drain-1", "drain-2"), socket.getWrittenText().lines().toList());
            assertFalse(client.send("after-close"));
            boolean stopped = awaitUntil(() -> countWriterThreads() == before, 1000);
            assertTrue("写线程未退出，仍有 " + countWriterThreads(), stopped);
        }

    }

    @Test(timeout = 1000)
    public void sendShouldFailFastWhenOutboundQueueIsFull() throws IOException, InterruptedException {

        BlockingSocket socket = new BlockingSocket();

        try (ClientService client = new ClientService(socket, new LinkedBlockingQueue<>(), new OnlineRegistryUtil())) {
            assertTrue(client.send("first"));
            assertTrue(socket.awaitWriteEntered(1, TimeUnit.SECONDS));

            int accepted = 1;
            boolean rejected;
            long start = System.nanoTime();
            while (accepted < 1000 && client.send("m" + accepted)) {
                accepted++;
            }
            rejected = accepted < 1000;
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertTrue("队列必须有上限", rejected);
            assertTrue("满载拒绝必须立刻返回", elapsedMs < 1000);
            assertFalse(client.send("still-full"));
            long t = System.nanoTime();
            client.close();
            assertTrue("关闭必须有界收敛", (System.nanoTime() - t) / 1_000_000 < 2000);

        }


    }

    @Test
    public void slowClientShouldNotBlockOtherClient() throws Exception {

        BlockingQueue<Message> queue = new LinkedBlockingQueue<>(10);
        OnlineRegistryUtil registry = new OnlineRegistryUtil();
        SendMsgService sendMsgService = new SendMsgService(queue, registry);
        ExecutorService singleThreadExecutor = Executors.newSingleThreadExecutor();

        try (BlockingSocket bobSocket = new BlockingSocket();
             RecordingSocket charlieSocket = new RecordingSocket();
             ClientService bobClient = new ClientService(bobSocket, new LinkedBlockingQueue<>(), registry);
             ClientService charlieClient = new ClientService(charlieSocket, new LinkedBlockingQueue<>(), registry)) {

            assertTrue(registry.register("Bob", bobClient));
            assertTrue(registry.register("Charlie", charlieClient));

            Message toBobMessage = new Message("Alice", "Bob", "hello Bob");
            Message toCharlieMessage = new Message("Alice", "Charlie", "hi Charlie");

            queue.add(toBobMessage);
            queue.add(toCharlieMessage);

            singleThreadExecutor.submit(sendMsgService);

            assertTrue(bobSocket.awaitWriteEntered(1, TimeUnit.SECONDS));
            assertTrue(charlieSocket.awaitMessage(1, TimeUnit.SECONDS));

            assertEquals(Constant.ServerOrder.MSG_RES + ",Alice,Charlie,hi Charlie",
                    charlieSocket.getWrittenText().trim());

            bobSocket.release();
            assertTrue(bobSocket.awaitMessage(1, TimeUnit.SECONDS));
            assertEquals(Constant.ServerOrder.MSG_RES + ",Alice,Bob,hello Bob",
                    bobSocket.getWrittenText().trim());

        } finally {

            sendMsgService.shutdown();
            singleThreadExecutor.shutdownNow();

        }

        assertTrue(singleThreadExecutor.awaitTermination(2, TimeUnit.SECONDS));


    }

    private static long countWriterThreads() {
        Set<Thread> threads = Thread.getAllStackTraces().keySet();
        return threads.stream()
                .filter(thread -> thread.getName().startsWith("chat-writer-"))
                .filter(Thread::isAlive)
                .count();
    }

    private static boolean awaitUntil(BooleanSupplier condition, long timeoutMs) throws InterruptedException {

        long time = System.nanoTime() + timeoutMs * 1_000_000;
        while (true) {
            if (condition.getAsBoolean()) {
                return true;
            }
            if (System.nanoTime() >= time) {
                return false;
            }
            Thread.sleep(10);
        }

    }

    /**
     * 阻塞型 Socket
     */
    private static class BlockingSocket extends Socket {
        private final CountDownLatch releaseLatch = new CountDownLatch(1);          // 控制阻塞与放行
        private final CountDownLatch writeEnteredLatch = new CountDownLatch(1);     // 确认写操作已经进入阻塞点
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();   // 缓存写入数据
        private final CountDownLatch messageWrittenLatch = new CountDownLatch(1);   // 确认写入消息已经完成

        private final OutputStream outputStream = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                writeEnteredLatch.countDown();
                try {
                    releaseLatch.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("写中断");
                }
                buffer.write(b);
                if ('\n' == b) {
                    messageWrittenLatch.countDown();
                }
            }
        };

        public boolean awaitMessage(long timeout, TimeUnit unit) throws InterruptedException {
            return messageWrittenLatch.await(timeout, unit);
        }

        public synchronized String getWrittenText() {
            return buffer.toString(StandardCharsets.UTF_8);
        }

        public boolean awaitWriteEntered(long timeout, TimeUnit unit) throws InterruptedException {
            return writeEnteredLatch.await(timeout, unit);
        }

        /**
         * 放行
         */
        public void release() {
            releaseLatch.countDown();
        }

        @Override
        public OutputStream getOutputStream() {
            return outputStream;
        }

        @Override
        public void close() throws IOException {
            release();
            super.close();
        }
    }

    /**
     * 可观测内存 Socket
     */
    private static class RecordingSocket extends Socket {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        CountDownLatch messageWrittenLatch = new CountDownLatch(1);
        OutputStream outputStream = new OutputStream() {
            @Override
            public synchronized void write(int b) {
                buffer.write(b);
                if ('\n' == b) {
                    messageWrittenLatch.countDown();
                }
            }
        };

        /**
         * 限时等待
         *
         * @param timeout 等待时间
         * @param unit    时间单位
         */
        public boolean awaitMessage(long timeout, TimeUnit unit) throws InterruptedException {
            return messageWrittenLatch.await(timeout, unit);
        }

        /**
         * 获取文本
         */
        public synchronized String getWrittenText() {
            return buffer.toString(StandardCharsets.UTF_8);
        }

        @Override
        public OutputStream getOutputStream() {
            return outputStream;
        }
    }


}
