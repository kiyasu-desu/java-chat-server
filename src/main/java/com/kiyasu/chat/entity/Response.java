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
public class Response {
    private Constant.ServerOrder orderType;
    private Result info;

    @Override
    public String toString() {
        return orderType.getOrderNo() + "," + info;
    }
}
