package u3123004589.service;

import u3123004589.common.Constant;
import u3123004589.entity.Message;

public interface UserService {
    Constant.ResultType register(String id, String password);

    Constant.ResultType login(String id, String password);

    Constant.ResultType logout(String id);

    Constant.ResultType search();

    Constant.ResultType sendMessage(Message message);
}
