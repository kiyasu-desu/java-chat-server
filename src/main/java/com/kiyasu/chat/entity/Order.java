package com.kiyasu.chat.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import com.kiyasu.chat.common.Constant;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class Order {
    //命令
    private Constant.UserOrder order;
    //数据
    private String content;
}
