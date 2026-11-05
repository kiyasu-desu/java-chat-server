package com.kiyasu.chat.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class Message {
    //发送者id
    private String srcId;
    //接收者id
    private String dstId;
    //消息
    private String msg;
    //群发标志
    private boolean broadcast;

    public Message(String srcId, String dstId, String msg) {
        this.srcId = srcId;
        this.dstId = dstId;
        this.msg = msg;
        this.broadcast = false;
    }

    public static Message broadcast(String srcId, String msg) {
        Message message = new Message();
        message.setBroadcast(true);
        message.setSrcId(srcId);
        message.setDstId(null);
        message.setMsg(msg);
        return message;
    }
}
