CREATE TABLE interface_release (              -- Dataway API 发布历史。
  pub_id           varchar(64)  NOT NULL PRIMARY KEY  COMMENT 'Publish ID',
  pub_api_id       varchar(64)  NOT NULL COMMENT '所属API ID',
  pub_method       varchar(12)  NOT NULL COMMENT 'HttpMethod：GET、PUT、POST',
  pub_path         varchar(512) NOT NULL COMMENT '拦截路径',
  pub_status       varchar(4)   NOT NULL COMMENT '状态：-1-删除, 0-草稿，1-发布，2-有变更，3-禁用',
  pub_comment      varchar(255) NOT NULL COMMENT '注释',
  pub_type         varchar(24)  NOT NULL COMMENT '脚本类型：SQL、DataQL',
  pub_script       CLOB         NOT NULL COMMENT '查询脚本：xxxxxxx',
  pub_script_ori   CLOB         NOT NULL COMMENT '原始查询脚本，仅当类型为SQL时不同',
  pub_schema       CLOB         NOT NULL COMMENT '接口的请求/响应数据结构',
  pub_sample       CLOB         NOT NULL COMMENT '请求/响应/请求头样本数据',
  pub_option       CLOB         NOT NULL COMMENT '扩展配置信息',
  pub_release_time varchar(32)  NOT NULL COMMENT '发布时间（下线不更新）'
);

CREATE INDEX idx_interface_release_api on interface_release (pub_api_id);
CREATE INDEX idx_interface_release_path on interface_release (pub_path);
