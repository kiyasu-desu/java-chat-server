package u3123004589.service;

import u3123004589.entity.Message;
import u3123004589.entity.Order;
import u3123004589.entity.Response;

import java.util.concurrent.BlockingQueue;

public interface UserService {

    Response runOrder(Order order, BlockingQueue<Message> sendQueue);
}
