package u3123004589.entity;

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
}
