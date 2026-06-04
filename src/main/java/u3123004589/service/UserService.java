package u3123004589.service;

import u3123004589.common.Constant;
import u3123004589.entity.Message;
import u3123004589.entity.Result;

public interface UserService {
    Result register(String id, String password);

    Result login(String id, String password);

    Result logout(String id);

    Result search();

    Result sendMessage(Message message);
}
