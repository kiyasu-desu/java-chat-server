package com.kiyasu.chat.service;

import com.kiyasu.chat.common.Constant;
import com.kiyasu.chat.entity.Message;
import com.kiyasu.chat.entity.Order;
import com.kiyasu.chat.entity.Response;
import com.kiyasu.chat.entity.Result;
import com.kiyasu.chat.service.impl.MessageServiceImpl;
import com.kiyasu.chat.service.impl.OrderServiceImpl;
import com.kiyasu.chat.util.OnlineRegistryUtil;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public class ClientService implements AutoCloseable, Runnable {

    private static final int DEFAULT_READ_TIMEOUT_MILLIS = 60_000;      // 默认读超时
    private static final int OUTBOUND_CAPACITY = 256;                   // 输出队列容量
    private static final String POISON = "\u0000";                      // 哨兵值, 用于标记写线程退出
    private static final int MAX_LINE_LENGTH = 1024;                    // 单行输入最大字符数

    private final Socket client;                                        // 客户端套接字
    private final MessageSubmissionService messageSubmissionService;    // 消息发送队列
    private String userId;                                              // 当前客户端对应的用户ID
    private final OnlineRegistryUtil registry;                          // 在线用户注册表
    private final PrintWriter out;                                      // 输出流, 用于向客户端发送消息
    private final BlockingQueue<String> outbound = new ArrayBlockingQueue<>(OUTBOUND_CAPACITY); // 输出队列, 用于存储待发送的消息
    private volatile boolean running = true;
    private final Thread writer;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AccountService accountService;
    private final Object writerLock = new Object();                     // 私有锁对象
    private boolean writerStarted = false;                              // 私有锁状态
    private final int readTimeoutMillis;                                // 读超时时间

    public ClientService(Socket client,
                         BlockingQueue<Message> sendQueue,
                         OnlineRegistryUtil registry) throws IOException {

        this(client, sendQueue, registry, DEFAULT_READ_TIMEOUT_MILLIS);
    }

    ClientService(Socket client,
                  BlockingQueue<Message> sendQueue,
                  OnlineRegistryUtil registry,
                  int readTimeoutMillis) throws IOException {
        this.readTimeoutMillis = readTimeoutMillis;
        this.client = client;
        client.setSoTimeout(readTimeoutMillis);
        this.accountService = new AccountService();
        this.messageSubmissionService = new MessageSubmissionService(sendQueue, registry);
        this.registry = registry;
        this.out = new PrintWriter(new OutputStreamWriter(client.getOutputStream()), true);
        this.writer = new Thread(this::writeLoop, "chat-writer-" + client.getRemoteSocketAddress());
        this.writer.setDaemon(true);
    }

    public boolean send(String text) {
        synchronized (writerLock) {
            if (closed.get() || !running) return false;
            if (!writerStarted) {
                writer.start();
                writerStarted = true;
            }
            return outbound.offer(text);
        }
    }

    @Override
    public void run() {
        try {
            BufferedReader br = new BufferedReader(new InputStreamReader(client.getInputStream()));

            if (!send("这里是kiyasu的聊天室, 输入回车继续...")) {
                return;
            }
            if (readBoundedLine(br) == null) {
                log.info("客户端 {} 连接已关闭", client.getRemoteSocketAddress());
                return;
            }
            if (!send("请输入命令: ")) {
                return;
            }

            while (true) {
                String inputLine = readBoundedLine(br);
                if (inputLine == null) {
                    log.info("客户端 {} 连接已关闭", client.getRemoteSocketAddress());
                    break;
                }

                OrderService orderService = new OrderServiceImpl();
                Order order = orderService.splitOrder(inputLine);
                if (order == null) {
                    if (!send(Constant.ServerOrder.NO_MEANS_REQ + ", 命令格式错误")) {
                        break;
                    }
                    continue;
                }

                Response response = runOrder(order);
                if (!send(response.toString())) {
                    break;
                }
                if (response.getOrderType() == Constant.ServerOrder.LOGOUT_RES
                        && response.getInfo().getResult() == Constant.ResultType.SUCCESS) {
                    break;
                }
            }
        } catch (SocketTimeoutException e) {
            log.info("客户端 {} 读空闲超时, 关闭连接", client.getRemoteSocketAddress());
        } catch (IOException e) {
            log.error("处理客户端 {} 的请求时发生错误: {}", client.getRemoteSocketAddress(), e.getMessage());
        } finally {
            if (userId != null) {
                registry.unregister(userId, this);
            }
            close();
        }
    }

    /**
     * 关闭客户端连接
     */
    @Override
    public void close() {
        synchronized (writerLock) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            running = false;
            if (writerStarted) {
                outbound.offer(POISON);
            }
        }
        joinWriter(300);
        try {
            client.close();
        } catch (IOException e) {
            log.error("关闭客户端 {} 连接时发生错误: {}", client.getRemoteSocketAddress(), e.getMessage());
        }
        writer.interrupt();
        joinWriter(1000);
        if (writer.isAlive()) {
            log.warn("写线程 {} 未能正常退出", writer.getName());
        }
    }

    private void joinWriter(int timeout) {
        try {
            writer.join(timeout);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 写线程循环, 从输出队列中取出消息并发送给客户端, 直到收到哨兵值或线程被中断
     */
    private void writeLoop() {
        String message;
        try {
            while (true) {
                message = outbound.take();
                if (POISON.equals(message)) {
                    break;
                }
                out.print(message);
                out.print('\n');
                out.flush();
                if (out.checkError()) {
                    running = false;
                    log.warn("向客户端 {} 发送消息时发生错误", client.getRemoteSocketAddress());
                    try {
                        client.close();
                    } catch (IOException e) {
                        log.error("关闭客户端 {} 连接时发生错误", client.getRemoteSocketAddress(), e);
                    }
                    break;
                }
            }
        } catch (InterruptedException e) {
            running = false;
            Thread.currentThread().interrupt();
            log.info("写线程收到中断, 退出");
        }
    }

    private Response runOrder(Order order) {
        Response response = new Response();
        Result result = new Result();

        if (order == null || order.getOrder() == null) {
            log.error("命令解析错误");
            response.setOrderType(Constant.ServerOrder.NO_MEANS_REQ);
            result.setResult(Constant.ResultType.FAIL);
            result.setInfo("命令解析错误");
        } else {
            switch (order.getOrder()) {
                //注册 001
                case REGISTER_REQ -> {
                    response.setOrderType(Constant.ServerOrder.REGISTER_RES);
                    if (null == order.getContent()) {
                        log.warn("注册请求参数为空");
                        result.setResult(Constant.ResultType.FAIL);
                        result.setInfo("注册请求参数为空");
                    } else {
                        String[] params = order.getContent().split(",");
                        if (params.length != 2) {
                            log.warn("注册请求参数错误");
                            result.setResult(Constant.ResultType.FAIL);
                            result.setInfo("注册请求参数错误");
                        } else {
                            result = accountService.register(params[0].trim(), params[1].trim());
                        }
                    }
                }

                //登录 002
                case LOGIN_REQ -> {
                    response.setOrderType(Constant.ServerOrder.LOGIN_RES);
                    if (isLogin()) {
                        log.warn("登录请求失败: 用户 {} 已登录", userId);
                        result.setResult(Constant.ResultType.FAIL);
                        result.setInfo("登录请求失败: 用户已登录");
                    } else {
                        if (null == order.getContent()) {
                            log.warn("登录请求参数为空");
                            result.setResult(Constant.ResultType.FAIL);
                            result.setInfo("登录请求参数为空");
                        } else {
                            String[] params = order.getContent().split(",");
                            if (params.length != 2) {
                                log.warn("登录请求参数错误");
                                result.setResult(Constant.ResultType.FAIL);
                                result.setInfo("登录请求参数错误");
                            } else {
                                result = login(params[0].trim(), params[1].trim());
                            }
                        }
                    }
                }

                //登出 003
                case LOGOUT_REQ -> {
                    response.setOrderType(Constant.ServerOrder.LOGOUT_RES);
                    if (!isLogin()) {
                        log.warn("登出请求失败: 用户未登录");
                        result.setResult(Constant.ResultType.FAIL);
                        result.setInfo("登出请求失败: 用户未登录");
                    } else {
                        String id = order.getContent();

                        if (id == null || id.isBlank()) {
                            log.warn("登出请求参数错误");
                            result.setResult(Constant.ResultType.FAIL);
                            result.setInfo("登出请求参数错误");
                        } else if (!userId.equals(id.trim())) {
                            log.warn("登出请求参数错误: 用户 {} 试图登出用户 {}", userId, id);
                            result.setResult(Constant.ResultType.FAIL);
                            result.setInfo("登出请求参数错误: 无权登出其他用户");
                        } else {
                            result = logout(id.trim());
                            if (result.getResult() == Constant.ResultType.SUCCESS) {
                                userId = null;
                            }
                        }
                    }
                }

                //搜索在线 004
                case FIND_REQ -> {
                    response.setOrderType(Constant.ServerOrder.FIND_RES);
                    List<String> ids = search();
                    result.setResult(Constant.ResultType.SUCCESS);
                    if (ids != null) {
                        result.setInfo(ids.isEmpty() ? "当前没有在线用户" : String.join(",", ids));
                    }
                }

                //发消息 005
                case SEND_MSG_REQ -> {
                    response.setOrderType(Constant.ServerOrder.NO_MEANS_REQ);
                    if (!isLogin()) {
                        log.warn("发送消息请求失败: 用户未登录");
                        result.setResult(Constant.ResultType.FAIL);
                        result.setInfo("发送消息请求失败: 用户未登录");
                    } else {
                        MessageService messageService = new MessageServiceImpl();
                        Message message = messageService.splitMessage(order.getContent());
                        if (null == message) {
                            log.warn("发送消息请求参数错误");
                            result.setResult(Constant.ResultType.FAIL);
                            result.setInfo("发送消息请求参数错误");
                        } else {
                            result = messageSubmissionService.submit(userId, message);
                        }
                    }
                }

                //群发消息 006
                case GROUP_MSG_REQ -> {
                    response.setOrderType(Constant.ServerOrder.NO_MEANS_REQ);
                    if (null == order.getContent()) {
                        log.warn("群发消息请求参数为空");
                        result.setResult(Constant.ResultType.FAIL);
                        result.setInfo("群发消息请求参数为空");
                    } else {
                        Message message = Message.broadcast(userId, order.getContent());
                        result = messageSubmissionService.submit(userId, message);
                    }
                }
            }
        }

        response.setInfo(result);
        return response;
    }


    private Result login(String id, String password) {
        Result result = accountService.verifyCredentials(id, password);
        if (result.getResult() == Constant.ResultType.SUCCESS) {
            if (!registry.register(id, this)) {
                log.warn("{} 登录失败: 用户已在线", id);
                result.setResult(Constant.ResultType.FAIL);
                result.setInfo("用户已在线");
            } else {
                userId = id;
                log.info("{} 登录成功", id);
                result.setResult(Constant.ResultType.SUCCESS);
                result.setInfo("登录成功");
            }
        }
        return result;
    }

    private Result logout(String id) {
        Result result = new Result();
        try {

            if (!registry.unregister(id, this)) {
                log.warn("{} 登出失败: 当前连接没有对应的在线记录", id);
                result.setResult(Constant.ResultType.FAIL);
                result.setInfo("用户已离线");
            } else {
                log.info("{} 登出成功", id);
                result.setResult(Constant.ResultType.SUCCESS);
                result.setInfo("登出成功");
            }
            return result;

        } catch (Exception e) {
            log.error("{} 登出失败: {}", id, e.getMessage());
            result.setResult(Constant.ResultType.FAIL);
            result.setInfo(e.getMessage());
            return result;
        }
    }

    private List<String> search() {
        try {
            log.info("{} 查询在线用户列表", userId);
            List<String> ids = registry.getOnlineIds();
            return ids == null ? List.of() : ids;
        } catch (Exception e) {
            log.error("查询在线用户列表失败: {}", e.getMessage());
            return null;
        }
    }

    private Boolean isLogin() {
        return userId != null;
    }

    private String readBoundedLine(BufferedReader br) throws IOException {
        StringBuilder sb = new StringBuilder();
        client.setSoTimeout(readTimeoutMillis);
        int tmp = br.read();
        if (tmp == -1) return null;
        if (tmp == '\n') return "";
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(readTimeoutMillis);
        long remainingNanos;
        do {
            if (sb.length() > MAX_LINE_LENGTH || (sb.length() == MAX_LINE_LENGTH && tmp != '\r')) {
                throw new IOException("单行输入长度超过上限: " + MAX_LINE_LENGTH);
            }
            sb.append((char) tmp);
            if (readTimeoutMillis > 0) {
                remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) {
                    throw new SocketTimeoutException("客户端读取整行命令超时");
                } else {
                    client.setSoTimeout((int) Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos)));
                }
            }
            tmp = br.read();
        } while ('\n' != tmp && -1 != tmp);
        client.setSoTimeout(readTimeoutMillis);
        if (sb.charAt(sb.length() - 1) == '\r') {
            sb.deleteCharAt(sb.length() - 1);
        }

        return sb.toString();
    }
}
