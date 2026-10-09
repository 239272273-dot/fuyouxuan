CREATE DATABASE IF NOT EXISTS fuyouxuan DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE fuyouxuan;

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

DROP TABLE IF EXISTS tb_blog_like;
DROP TABLE IF EXISTS tb_follow;
DROP TABLE IF EXISTS tb_blog;
DROP TABLE IF EXISTS tb_voucher_order;
DROP TABLE IF EXISTS tb_seckill_voucher;
DROP TABLE IF EXISTS tb_voucher;
DROP TABLE IF EXISTS tb_shop;
DROP TABLE IF EXISTS tb_shop_type;
DROP TABLE IF EXISTS tb_user_info;
DROP TABLE IF EXISTS tb_user;

CREATE TABLE tb_user (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  phone VARCHAR(11) NOT NULL,
  password VARCHAR(128) NOT NULL DEFAULT '',
  nick_name VARCHAR(32) NOT NULL,
  icon VARCHAR(255) NOT NULL DEFAULT '',
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_phone (phone)
) ENGINE=InnoDB;

CREATE TABLE tb_user_info (
  user_id BIGINT UNSIGNED NOT NULL,
  city VARCHAR(64) NOT NULL DEFAULT '',
  introduce VARCHAR(128) DEFAULT NULL,
  fans INT UNSIGNED NOT NULL DEFAULT 0,
  followee INT UNSIGNED NOT NULL DEFAULT 0,
  gender TINYINT UNSIGNED NOT NULL DEFAULT 0,
  birthday DATE DEFAULT NULL,
  credits INT UNSIGNED NOT NULL DEFAULT 0,
  level TINYINT UNSIGNED NOT NULL DEFAULT 0,
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (user_id)
) ENGINE=InnoDB;

CREATE TABLE tb_shop_type (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  name VARCHAR(32) NOT NULL,
  icon VARCHAR(255) NOT NULL DEFAULT '',
  sort INT UNSIGNED NOT NULL DEFAULT 0,
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id)
) ENGINE=InnoDB;

CREATE TABLE tb_shop (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  name VARCHAR(128) NOT NULL,
  type_id BIGINT UNSIGNED NOT NULL,
  images VARCHAR(1024) NOT NULL DEFAULT '',
  area VARCHAR(128) DEFAULT NULL,
  address VARCHAR(255) NOT NULL,
  x DOUBLE UNSIGNED NOT NULL DEFAULT 0,
  y DOUBLE UNSIGNED NOT NULL DEFAULT 0,
  avg_price BIGINT UNSIGNED DEFAULT NULL,
  sold INT UNSIGNED NOT NULL DEFAULT 0,
  comments INT UNSIGNED NOT NULL DEFAULT 0,
  score INT UNSIGNED NOT NULL DEFAULT 50,
  open_hours VARCHAR(64) DEFAULT NULL,
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_shop_type (type_id)
) ENGINE=InnoDB;

CREATE TABLE tb_voucher (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  shop_id BIGINT UNSIGNED NOT NULL,
  title VARCHAR(255) NOT NULL,
  sub_title VARCHAR(255) DEFAULT NULL,
  rules VARCHAR(1024) DEFAULT NULL,
  pay_value BIGINT UNSIGNED NOT NULL,
  actual_value BIGINT NOT NULL,
  type TINYINT UNSIGNED NOT NULL DEFAULT 0,
  status TINYINT UNSIGNED NOT NULL DEFAULT 1,
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_voucher_shop (shop_id)
) ENGINE=InnoDB;

CREATE TABLE tb_seckill_voucher (
  voucher_id BIGINT UNSIGNED NOT NULL,
  stock INT NOT NULL,
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  begin_time TIMESTAMP NOT NULL,
  end_time TIMESTAMP NOT NULL,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (voucher_id)
) ENGINE=InnoDB;

CREATE TABLE tb_voucher_order (
  id BIGINT NOT NULL,
  user_id BIGINT UNSIGNED NOT NULL,
  voucher_id BIGINT UNSIGNED NOT NULL,
  pay_type TINYINT UNSIGNED NOT NULL DEFAULT 1,
  status TINYINT UNSIGNED NOT NULL DEFAULT 1,
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  pay_time TIMESTAMP NULL DEFAULT NULL,
  use_time TIMESTAMP NULL DEFAULT NULL,
  refund_time TIMESTAMP NULL DEFAULT NULL,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_order_user_voucher (user_id, voucher_id),
  KEY idx_order_user_time (user_id, create_time)
) ENGINE=InnoDB;

CREATE TABLE tb_blog (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  shop_id BIGINT NOT NULL DEFAULT 1,
  user_id BIGINT UNSIGNED NOT NULL,
  title VARCHAR(255) NOT NULL,
  images VARCHAR(2048) NOT NULL DEFAULT '',
  content VARCHAR(2048) NOT NULL,
  liked INT UNSIGNED NOT NULL DEFAULT 0,
  comments INT UNSIGNED NOT NULL DEFAULT 0,
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_blog_user_time (user_id, create_time),
  KEY idx_blog_liked (liked)
) ENGINE=InnoDB;

CREATE TABLE tb_blog_like (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  blog_id BIGINT UNSIGNED NOT NULL,
  user_id BIGINT UNSIGNED NOT NULL,
  create_time BIGINT NOT NULL,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_blog_user (blog_id, user_id)
) ENGINE=InnoDB;

CREATE TABLE tb_follow (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id BIGINT UNSIGNED NOT NULL,
  follow_user_id BIGINT UNSIGNED NOT NULL,
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_follow_relation (user_id, follow_user_id)
) ENGINE=InnoDB;

INSERT INTO tb_user (id, phone, nick_name, icon) VALUES
  (1, '13686869696', '林知夏 · 人力资源中心', '/assets/avatars/hr.svg'),
  (2, '13838411438', '周亦然 · 研发中心', '/assets/avatars/engineering.svg'),
  (3, '13900000003', '顾清和 · 信息技术部', '/assets/avatars/it.svg'),
  (4, '13700000004', '沈嘉宁 · 行政服务部', '/assets/avatars/workplace.svg');

INSERT INTO tb_user_info (user_id, introduce, credits, level) VALUES
  (1, '员工福利与人才发展', 860, 3),
  (2, '企业平台研发与架构实践', 720, 3),
  (3, '办公系统、账号与安全服务', 680, 2),
  (4, '园区服务与员工关怀', 590, 2);

INSERT INTO tb_shop_type (id, name, sort) VALUES
  (1, '节日关怀', 1),
  (2, '餐饮补贴', 2),
  (3, '健康生活', 3),
  (4, '学习成长', 4);

INSERT INTO tb_shop (id, name, type_id, images, area, address, avg_price, sold, comments, score, open_hours) VALUES
  (1, '中秋暖心礼盒', 1, '/assets/benefits/festival.svg', '员工关怀', '全国范围内企业员工专享', 29900, 1280, 0, 50, '活动期内有效'),
  (2, '工作日能量餐包', 2, '/assets/benefits/meal.svg', '餐饮补贴', '合作园区餐厅通用', 5000, 3420, 0, 50, '工作日 11:00-14:00'),
  (3, '年度健康体检', 3, '/assets/benefits/health.svg', '健康关爱', '全国合作体检中心可预约', 89900, 760, 0, 50, '预约后90天内有效'),
  (4, '在线学习年卡', 4, '/assets/benefits/learning.svg', '学习成长', '企业学习平台全课程', 69900, 640, 0, 50, '激活后365天有效'),
  (5, '生日专属心意', 1, '/assets/benefits/festival.svg', '生日关怀', '生日当月员工可领取', 20000, 980, 0, 50, '生日当月有效'),
  (6, '运动健身月卡', 3, '/assets/benefits/health.svg', '活力计划', '合作健身场馆通用', 29900, 510, 0, 50, '领取后30天内激活');

INSERT INTO tb_voucher (id, shop_id, title, sub_title, rules, pay_value, actual_value, type, status) VALUES
  (1, 1, '中秋礼盒限量抢购', '企业补贴后员工专享价', '每位员工限领一份；领取成功后由行政统一发放', 9900, 29900, 1, 1),
  (2, 2, '50元工作餐券', '园区合作餐厅通用', '每位员工限领一张；不可兑换现金', 1000, 5000, 1, 1),
  (3, 3, '年度体检升级包', '覆盖30项常用检查', '预约后按体检中心指引使用', 19900, 89900, 1, 1),
  (4, 4, '学习平台年度会员', '技术与通识课程不限次学习', '账号权益不可转让', 9900, 69900, 1, 1),
  (5, 5, '生日蛋糕兑换券', '生日当月暖心福利', '仅限生日当月领取', 0, 20000, 1, 1),
  (6, 6, '健身月卡体验权益', '合作场馆任选其一', '领取后30天内激活', 5900, 29900, 1, 1);

INSERT INTO tb_seckill_voucher (voucher_id, stock, begin_time, end_time) VALUES
  (1, 1000, '2026-01-01 00:00:00', '2035-12-31 23:59:59'),
  (2, 500, '2026-01-01 00:00:00', '2035-12-31 23:59:59'),
  (3, 300, '2026-01-01 00:00:00', '2035-12-31 23:59:59'),
  (4, 200, '2026-01-01 00:00:00', '2035-12-31 23:59:59'),
  (5, 500, '2026-01-01 00:00:00', '2035-12-31 23:59:59'),
  (6, 200, '2026-01-01 00:00:00', '2035-12-31 23:59:59');

INSERT INTO tb_blog (id, shop_id, user_id, title, images, content, liked) VALUES
  (1, 1, 1, '【福利公告】中秋礼盒开放领取', '/assets/company/hr-announcement.svg', '本年度中秋员工礼盒已在福利中心开放。每位在职员工限领一份，成功领取后将由各园区行政统一通知发放时间。', 18),
  (2, 1, 3, '【IT提醒】请完成企业账号安全检查', '/assets/company/security-notice.svg', '请大家在本周五前检查企业账号的多因素认证状态。不要向任何人提供验证码；如发现异常登录，请立即联系信息技术部。', 12),
  (3, 1, 2, '研发分享：订单链路的幂等与补偿', '/assets/company/engineering-share.svg', '本周技术午餐会将结合福利秒杀链路，分享 Redis Lua、RocketMQ 重试、消费幂等和死信补偿的工程实践，欢迎感兴趣的同事参加。', 26),
  (4, 1, 4, '【园区通知】国庆期间班车安排', '/assets/company/campus-notice.svg', '节日期间园区班车将按假日时刻表运行，具体班次已同步至行政服务台。需要临时加班的同事可提前一天登记。', 9);

INSERT INTO tb_follow (user_id, follow_user_id) VALUES (2, 1), (2, 3), (2, 4), (1, 2), (4, 1);
INSERT INTO tb_blog_like (blog_id, user_id, create_time) VALUES (1, 2, 1789992000000), (3, 2, 1789992100000);

SET FOREIGN_KEY_CHECKS = 1;
