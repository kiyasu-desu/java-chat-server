package com.kiyasu.chat.service;

import com.kiyasu.chat.entity.Message;

public interface MessageService {
    Message splitMessage(String str);
}
