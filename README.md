# Havefun-Community
项目介绍：
一款基于 Spring Boot 开发的在线点评系统，实现了用户登录、优惠券秒杀、达人探店、好友点赞、和关注、用户签到等功能。旨在为用户提供查看提供附近消费场所，提供真实、优惠的店铺信息
项目亮点：
  基于 Redis 解决多台集群的用户 session 共享问题。
  使用 Redis 进行商铺查询缓存，并对缓存穿透、缓存雪崩、缓存击穿等问题进行解决。
  使用 Redis 加锁解决优惠券秒杀的一人一单和超卖问题，Redis 分布式锁解决集群下的锁失效问题。
  使用消息队队列实现秒杀异步下单，提高系统并发性能。
  实现推模式的 Feed 流功能，将用户发送的笔记投放到其粉丝的邮箱中。

功能介绍：
1.用户登录模块：
用户登录做的是手机号验证码登录。
2.商户查询模块：
3.优惠券秒杀模块
4.秒杀改进--消息队列实现异步下单
5.发布笔记
6.好友关注、点赞
7.消息推送

优化改进的点：
