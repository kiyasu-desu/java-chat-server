package u3123004589.service;

import u3123004589.common.Constant;
import u3123004589.entity.Message;
import u3123004589.entity.Order;
import u3123004589.entity.Response;
import u3123004589.entity.Result;

import java.util.List;

public interface UserService {

    Response runOrder(Order order);
}
