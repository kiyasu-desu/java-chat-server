CREATE database if NOT EXISTS `chat-app`;
use `chat-app`;

CREATE TABLE user
(
    id       VARCHAR(32)                NOT NULL COMMENT '用户ID',
    password CHAR(32)                   NOT NULL COMMENT '密码(md5加密)',
    status   enum ('ONLINE', 'OFFLINE') NOT NULL DEFAULT 'OFFLINE' COMMENT '登录状态',
    PRIMARY KEY (id)
)