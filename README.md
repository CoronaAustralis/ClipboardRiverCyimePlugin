# Clipboard River · CyIME 独立插件

只同步纯文本：手机新复制的文本通过 HTTP 上传，WebSocket 收到的最新文本在键盘显示期间约每 2 秒交给 CyIME 写入系统剪切板。

## 安装和配置

1. 在 CyIME 插件管理中导入xipk文件。
2. 直接点击 **Clipboard River 文本同步** 插件卡片进入本插件设置；「去选择」会进入宿主共用的「剪贴板与同步」页，服务选择位于「备用：手动连接与其他服务」中。
3. 填写服务端基础地址，保存设置，并在插件设置的「联网」区域允许该域名。
4. 在 Clipboard River 管理页开启五分钟入网窗口，填写本次窗口的入网码后点击「注册设备」。窗口过期、手动关闭、重新开启或服务端重启后，旧码不可用。设备 UUID 和返回的 token 自动保存在本插件自己的配置中；入网窗口关闭不影响已注册设备连接。
5. 点击「测试 HTTP 连接」（调用 `device/profile`），成功后在「剪贴板与同步 → 备用：手动连接与其他服务」选择本插件并打开「启用所选兼容服务」，再打开键盘开始接收。实时收发还要求服务端允许来源设备发送、允许本设备接收。

以当前参考源码 CyIME **1.15.82** 为最低版本，使用宿主通用 `clipboard_sync` 能力。它是单选服务：选用本插件时，其他同步服务不同时运行。

## 范围和限制

- 只处理 `clipboard.created` 中的 `content_kind=text`、`upload_kind=realtime`；不处理文件、图片、历史、批量回填或离线补拉。忽略 `clipboard.sync_changed`，不调用可选的 `sync/text`。WebSocket 不会补发离线期间错过的消息。
- WebSocket 在第一次可用的键盘轮询时连接。健康连接下，接收后通常在下一次约 2 秒的宿主轮询写入；初次握手、网络重连和宿主错误退避会增加延迟。短时间多条消息只保留最新一条。
- **纯 xipk 没有键盘收起/同步停止通知。** 收起键盘或关闭宿主同步开关后不再写入远端内容，但插件已建立的 WebSocket 可能继续在线、保留最新收到的文本；再次打开键盘并启用同步时应用它。停用/卸载插件、宿主卸载运行实例或进程结束才关闭连接。不能宣称收起即离线。
- 手机复制上传依赖 CyIME 通用接口的系统剪切板监听和 Android 读取权限；输入法服务没有运行时不会上传。不主动发送安装前的剪切板历史。敏感标记、隐身模式等内容按宿主规则跳过，空白文本及 NUL 不同步。
- 写回和防止收到的文本再次上传，使用输入法的通用剪切板同步引擎。插件自身独立实现设备注册、Bearer 认证、WebSocket hello、文本上传和断线重连。只发送初始 hello，不发送旧协议的 cursor、ack 或应用层 ping。
- 新协议没有 `client_item_id` 或幂等上传：每次接收成功都会新增记录。上传失败或回执不确定时，插件记录错误，并结束本次复制事件以阻止宿主自动重发；这不表示服务端已收到。检查插件错误记录后，可以重新复制文本再试，即使内容相同也会重新上传。
- 文本上传不提供可选的 `client_created_at`，由服务端使用接收时间。接收侧按事件到达顺序保留最后一条文本；`item_id` 只用于身份与相邻重复检查，不将 ID 大小当作时间顺序或同步游标。
- 更换服务端后必须重新注册；不会把旧服务端的 token 发给新地址。设备被删除或凭据失效时，先在服务端检查，再在当前开放的入网窗口注册。窗口内入网码不限次数，但重新注册会使同设备旧 token 失效。
- HTTPS 用于跨网络连接；HTTP 传输不加密，仅适合可信局域网。

## 重新打包

```powershell
powershell -ExecutionPolicy Bypass -File .\plugins\clipboard-river\build.ps1
```

包内只有 `manifest.yaml` 和 `main.lua`，不包含凭据、测试代码或宿主代码。

本地协议检查使用与宿主一致的 LuaJ 3.0.1，见 `check.kt`。在当前项目根目录运行（需要 JDK 和 Kotlin 编译器，编译器不在 PATH 时使用其完整路径）：

```powershell
$checkDir = Join-Path $env:TEMP 'clipboard-river-plugin-check'
New-Item -ItemType Directory -Force -Path $checkDir | Out-Null
$luaJar = Join-Path $checkDir 'luaj-jse-3.0.1.jar'
Invoke-WebRequest 'https://repo.maven.apache.org/maven2/org/luaj/luaj-jse/3.0.1/luaj-jse-3.0.1.jar' -OutFile $luaJar
kotlinc plugins/clipboard-river/check.kt CyIME/plugin-core/src/main/java/com/kingzcheung/xime/plugin/core/lua/sdk/SimpleJson.kt -classpath $luaJar -include-runtime -d (Join-Path $checkDir 'checks.jar')
java -Dfile.encoding=UTF-8 -cp "$(Join-Path $checkDir 'checks.jar');$luaJar" CheckKt plugins/clipboard-river/main.lua
```

已通过 13 组本地协议检查：注册字段与稳定身份、数字字符串/中文/表情/JSON 转义、简化上传回执、WebSocket hello 与本地接收确认、无关事件过滤、按到达顺序接收、重连退避、连接超时、切换服务端隔离、反向代理路径前缀、错误反馈、服务端入网失败原因和上传不盲重发。路径检查覆盖注册、profile、上传、WebSocket、尾斜杠、多级目录和同域不同路径的凭据隔离。HTTP/WS 使用模拟端点，JSON 编解码和 Lua 字符串使用真实 LuaJ 与参考宿主源码。
