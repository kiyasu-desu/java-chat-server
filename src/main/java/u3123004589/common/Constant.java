package u3123004589.common;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 通用的枚举值
 */
public class Constant {

    @AllArgsConstructor
    @NoArgsConstructor
    @Getter
    public enum OrderType {
        NO_MEANS_REQ("000", "无意义"),
        REGISTER_REQ("001", "注册"),
        LOGIN_REQ("002", "登录"),
        LOGOUT_REQ("003", "登出"),
        FIND_REQ("004", "查找用户"),
        SEND_MSG_REQ("005", "发送消息"),
        REGISTER_RES("101", "注册结果"),
        LOGIN_RES("102", "登录结果"),
        LOGOUT_RES("103", "登出结果"),
        FIND_RES("104", "查找用户结果"),
        GROUP_MSG_RES("105", "服务器群发消息"),
        MSG_RES("106", "服务器转发消息");

        private String orderNo;
        private String info;

        @Override
        public String toString() {
            return orderNo;
        }
    }

    //结果数据
    @AllArgsConstructor
    @NoArgsConstructor
    @Getter
    public enum ResultType {
        SUCCESS(0), FAIL(1);

        private int result;

        @Override
        public String toString() {
            if (result == 0) return "SUCCESS";
            return "FAIL";
        }
    }

}
