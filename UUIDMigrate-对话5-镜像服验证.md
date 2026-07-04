# UUIDMigrate 对话5：镜像服验证

## 职责

- 负责镜像服环境验证
- 负责日志排查
- 负责命令复现与回归
- 负责判断修复后是否真的可用

## 当前重点

- 重点验证：
  - `uuidmigrate admin prepare quickshop`
  - 修复后是否引入新报错
  - 是否影响 `scan / report / prepare residence`
- 检查镜像服日志、配置、插件状态

## 不负责

- 不承担主实现开发
- 不负责最终文档收口

## 首句提示词

```text
你只负责 UUIDMigrate 的镜像服验证、日志排查、命令复现和回归确认，不负责主功能开发。开始前请先阅读 K:\ideaxiangmu\UUIDMigrate\UUIDMigrate-多对话协作说明.md、K:\ideaxiangmu\UUIDMigrate\agent.md、K:\ideaxiangmu\UUIDMigrate\UUIDMigrate-交接进度.md，并把镜像服路径 D:\1.21.10\1.21.10 视为主要验证环境。请先输出验证清单，再围绕 quickshop prepare、scan、report、residence prepare 做复现与风险判断。
```
