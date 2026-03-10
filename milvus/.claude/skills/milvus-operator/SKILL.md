---
name: MILVUS OPERATOR
description: 这个skill是用来操作Milvus数据库的
version: 1.0.0
---

#  MILVUS OPERATOR
1. 统一使用ConnectionPoolManager 来管理milvus 连接
2. 所有的milvus 操作都需要捕获异常，并且打印日志
3. 可以参考DBOperator 类，里面已经实现了常用的milvus 操作
4. 统一使用milvus v2 client,如果想了解功能，可以本地解压源码包，进行查看
5. 所有的milvus 操作都需要保证线程安全