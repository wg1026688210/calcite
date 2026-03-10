---
name: E2E TEST
description: 这个skill是 端到端测试的要求
version: 1.0.0
---

# E2E TEST现成的工具类
这个项目是maven 项目，目前已存在的工具类：
- MilvusBaseE2ETest： 提供了milvus的基础连接和断开连接功能
- MilvusExtension ： 提供了milvus的docker 环境支持
- DBOperatorTest ： 已经实现的单测类

# 要求：
1. 一定要使用maven执行单测命令.
2. 执行记录要保留在.claude/logs/maven-test.log 文件中，方便后续查看.
3. 测试一定要跑通.