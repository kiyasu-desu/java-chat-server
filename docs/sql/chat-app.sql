CREATE database if NOT EXISTS `chat-app`;
use `chat-app`;

CREATE TABLE `user`
(
    id       VARCHAR(32) NOT NULL COMMENT '用户ID',
    password CHAR(32)    NOT NULL COMMENT '密码(md5加密)',
    PRIMARY KEY (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;