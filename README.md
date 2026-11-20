# seekseek android

第三方 DeepSeek 安卓客户端。界面用官方开源的 chat-demo 前端，后端接**任意 OpenAI 兼容服务商**——手机上跑本地模型（PocketPal 等）、局域网机器、或者云端 API 都行。

> 宣传视频：https://www.bilibili.com/video/BV1BVyWzoECd/

## 特性

- **官方界面**：完整复用 DeepSeek chat-demo 的界面，markdown、代码块、会话历史都在
- **任意服务商**：填地址、模型名、API Key 即可，不绑定任何一家
- **流式输出**：逐字显示，思考过程单独成块
- **联网搜索**：代理自己抓 Bing 结果并注入上下文，任何模型都能用，不依赖模型本身的工具调用能力
- **朗读**：调用 Android 系统 TTS
- **语音输入**：调用系统语音识别（需要设备装有识别服务）
- **文件上传**：文本类文件（txt/md/json/csv/代码）直接读入上下文
- **图片输入**：接多模态模型（如 `qwen2.5vl:7b`）时可以直接发图让它看图
- **导入导出**：历史对话 ZIP 导入，导出到「下载」目录
- **体积小**：约 1.9 MB，不含模型

## 下载

到 [Releases](../../releases) 下载 `seekseek-x.x.x.apk` 安装。

首次启动会让你填服务商：

| 字段 | 示例 |
|---|---|
| 服务商地址 | `http://127.0.0.1:8080/v1`（PocketPal 本地模型）或 `https://api.deepseek.com/v1` |
| 模型 | 留空则自动选第一个 |
| API Key | 本地服务随便填，云端填真 Key |

### 本地模型怎么跑

这个 App **不自带推理引擎**，需要先装一个能对外提供 OpenAI 兼容接口的加载器：

1. 安装 [PocketPal AI](https://github.com/a-ghorbani/pocketpal-ai)，下载一个 GGUF 模型（1.5B 的 Q3 量化约 900MB）
2. 在 PocketPal 设置里打开「本地 API 服务器」，端口 8080
3. 在本 App 里填 `http://127.0.0.1:8080/v1`，模型留空

## 从源码构建

> 只有想自己改代码的人才需要看这一节。

需要 JDK 17 和 Android SDK（build-tools 34、platform 34）。脚本里的路径按本机情况改：

```bash
bash build.sh
```

产物：`build/seekseek-1.0.0.apk`。

签名：脚本优先用根目录的 `release.keystore`（密码从 `release.keystore.pwd` 读），没有就用调试密钥。

## 项目结构

```
app/AndroidManifest.xml   清单
app/java/                 Java 源码（无第三方依赖，直接 javac + d8 构建）
app/assets/web/           官方 chat-demo 前端产物（MIT，见 app/assets/web/LICENSE）
app/assets/web/inject.js  注入脚本：桥接原生能力、改写请求
app/res/                  图标等资源
build.sh                  一键构建脚本
```

## 工作原理

```
官方网页 → 本地 127.0.0.1:8788（Java 写的迷你 HTTP 服务）
            ├─ 静态文件：直接吐 app/assets/web
            ├─ /anthropic/v1/messages：协议转换（Anthropic → OpenAI），流式回传
            └─ /v1/tools/*：朗读走系统 TTS，语音识别走系统识别
```

网页和接口同源，不涉及跨域；配置存在 SharedPreferences。

## 说明

- 本项目是**第三方客户端**，与 DeepSeek 官方无关
- `app/assets/web` 内的前端产物来自官方开源的 chat-demo，遵循其 MIT 许可证，版权归 DeepSeek 所有
- 语音输入依赖系统的语音识别服务，模拟器或精简系统（如 WSA）通常没有，会提示识别失败

## 许可证

MIT，见 [LICENSE](LICENSE)。
