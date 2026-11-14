-- Independent Clipboard River transport. Clipboard access and echo suppression belong to CyIME.
local plugin = {}
local APP_NAME, VERSION = "Clipboard River CyIME", "0.2.0"
local active, pending, lastAppliedId
local ready, startedAt, retryAt, retryDelay = false, 0, 0, 2

local function trim(value) return (value or ""):match("^%s*(.-)%s*$") end
-- The host's LuaJ table-to-JSON bridge treats strings such as "123" as numbers.
-- Encode our outbound objects here so copied numbers and numeric enrollment codes stay strings.
local function encode(value)
    if type(value) == "string" then
        return '"' .. value:gsub('[%z\1-\31\\"]', function(c)
            return string.format("\\u%04x", c:byte())
        end) .. '"'
    end
    if type(value) == "table" then
        local fields = {}
        for key, item in pairs(value) do fields[#fields + 1] = encode(key) .. ":" .. encode(item) end
        return "{" .. table.concat(fields, ",") .. "}"
    end
    if value == nil then return "null" end
    return tostring(value)
end

local function decode(value)
    if type(value) ~= "string" then return nil end
    local ok, result = pcall(host.json.decode, value)
    if ok and type(result) == "table" then return result end
end

local function serverUrl()
    local base = trim(host.config.get("serverUrl")):gsub("/+$", "")
    local scheme, authority = base:match("^(https?)://([^/]+)")
    if not scheme or authority:find("@") or base:find("[%c%s?#\\]") then
        error("请填写有效的 HTTP/HTTPS 服务端地址，可带路径前缀，例如 https://clipboard.example.com/river")
    end
    return base
end

local function disconnect()
    active, pending, lastAppliedId = nil, nil, nil
    ready, startedAt, retryAt, retryDelay = false, 0, 0, 2
    host.ws.close()
end

local function connection()
    local base = serverUrl()
    local saved = decode(host.config.get("registration"))
    if not saved or saved.server ~= base or type(saved.device_token) ~= "string"
        or saved.device_token == "" or type(saved.device_uuid) ~= "string"
        or type(saved.device_id) ~= "number" then
        if active then disconnect() end
        error("请先填写入网码并点击注册设备；更换服务端后需要重新注册")
    end
    local key = base .. "\n" .. saved.device_token
    if not active or active.key ~= key then
        disconnect()
        active = {key = key, base = base, token = saved.device_token,
            uuid = saved.device_uuid, deviceId = saved.device_id}
    end
    return active
end

local function current(c)
    local saved = decode(host.config.get("registration"))
    return active == c and trim(host.config.get("serverUrl")):gsub("/+$", "") == c.base
        and saved and saved.device_token == c.token and saved.server == c.base
end

local function post(base, path, data, token)
    local headers = {["Content-Type"] = "application/json", Accept = "application/json"}
    if token then headers.Authorization = "Bearer " .. token end
    -- LuaJ strings use CESU-8 internally. Convert request bodies explicitly to real UTF-8.
    local response = host.http.request("POST", base .. "/api/v1/client/" .. path,
        headers, host.bin.utf8(encode(data)), 5000)
    if not response then error("无法连接 Clipboard River 服务，请检查地址和联网授权") end
    local result = decode(response.text)
    if response.status < 200 or response.status >= 300 then
        local reason = result and type(result.error) == "string" and result.error:sub(1, 300) or ""
        local message = "Clipboard River 请求失败（HTTP " .. response.status .. "）"
        if response.status == 401 then message = message .. "，设备凭据已失效，请重新注册" end
        if not token and response.status == 400 then message = message .. "，请检查入网窗口和当前入网码" end
        error(message .. (reason ~= "" and "：" .. reason or ""))
    end
    if not result then error("服务端没有返回有效的 JSON 数据") end
    return result
end

local function send(kind, payload)
    host.ws.sendText(encode({type = kind, payload = payload}))
end

local function socketFailed(c)
    if not current(c) then return end
    ready = false
    retryAt = host.crypto.epochSeconds() + retryDelay
    retryDelay = math.min(retryDelay * 2, 30)
    -- Do not log server payloads, clipboard text, credentials or full URLs.
    host.logError("Clipboard River 实时连接断开，将在键盘显示时重连")
end

local function ensureSocket(c)
    local now = host.crypto.epochSeconds()
    local state = host.ws.getState()
    if now < retryAt then return end
    if state == 1 or state == 2 then
        if ready or now - startedAt < 15 then return end
        host.ws.close() -- Connected but hello_ack never arrived.
        socketFailed(c)
        return
    end
    host.ws.close() -- CyIME retains its failed socket until explicitly closed.
    ready, startedAt = false, now
    local url = c.base:gsub("^http", "ws") .. "/api/v1/client/ws"
    local accepted = host.ws.connect(url, {}, {
        onOpen = function()
            if current(c) then
                send("hello", {device_token = c.token, device_uuid = c.uuid})
            end
        end,
        onMessage = function(raw)
            if not current(c) then return end
            local message = decode(raw)
            if not message then return end
            if message.type == "hello_ack" then
                if type(message.payload) == "table" and message.payload.device_id == c.deviceId then
                    ready, retryAt, retryDelay = true, 0, 2
                end
                return
            end
            if message.type == "error" then
                socketFailed(c)
                host.ws.close()
                return
            end
            if not ready or message.type ~= "clipboard.created" then return end
            local item = message.item
            if type(item) ~= "table" or item.content_kind ~= "text" or item.upload_kind ~= "realtime"
                or item.source_device_id == c.deviceId or type(item.item_id) ~= "number" or item.item_id <= 0
                or item.item_id == lastAppliedId or (pending and item.item_id == pending.itemId)
                or type(item.text_content) ~= "string" or item.text_content:match("^%s*$")
                or item.text_content:find("%z") then return end
            local bytes = host.bin.utf8(item.text_content)
            -- A clipboard holds one current value: coalesce bursts to their newest text.
            pending = {type = "text", text = item.text_content, has_data = false,
                hash = host.crypto.hex(host.crypto.sha256(bytes)), size = #bytes,
                source = "Clipboard River", itemId = item.item_id}
        end,
        onError = function() socketFailed(c) end,
        onClose = function() socketFailed(c) end,
    })
    if not accepted then socketFailed(c) end
end

function plugin.getSettingsSchema()
    return {
        {key = "serverUrl", label = "服务端地址", type = "text", required = true,
            placeholder = "https://clipboard.example.com",
            helpText = "支持路径前缀，例如 https://example.com/river；接口会接在此前缀后。HTTP 仅适合可信局域网，保存后允许此域名联网。"},
        {key = "enrollmentCode", label = "入网码", type = "secret",
            helpText = "在管理页开启五分钟入网窗口，填写当前入网码。注册成功后自动保存设备凭据。"},
        {key = "registerDevice", label = "注册设备", type = "button",
            helpText = "首次连接或设备凭据失效时点击；重新注册会替换旧凭据，入网窗口关闭后无需重复注册。"},
        {key = "testConnection", label = "测试 HTTP 连接", type = "button"},
    }
end

function plugin.onLoad()
    if not host.ws or not host.http or not host.crypto or not host.bin or not host.bin.utf8 then
        error("当前 CyIME 缺少插件需要的网络和 UTF-8 接口，请更新输入法")
    end
    return true
end

function plugin.onUnload() disconnect() end

function plugin.registerDevice()
    local base = serverUrl()
    local code = trim(host.config.get("enrollmentCode"))
    if code == "" then return "请填写服务端管理页生成的入网码" end
    local uuid = host.config.get("deviceUuid")
    if not uuid or uuid == "" then
        uuid = host.uuid()
        host.config.set("deviceUuid", uuid)
    end
    local result = post(base, "register", {device_uuid = uuid, enrollment_code = code,
        nickname = "CyIME Android", app_name = APP_NAME, app_version = VERSION})
    if serverUrl() ~= base then return "服务端地址已更改，请重新注册" end
    if type(result.device_token) ~= "string" or result.device_token == ""
        or type(result.device_id) ~= "number" then return "注册响应缺少设备凭据" end
    host.config.set("registration", encode({server = base, device_uuid = uuid,
        device_id = result.device_id, device_token = result.device_token}))
    disconnect()
    return nil
end

function plugin.testConnection()
    local c = connection()
    local result = post(c.base, "device/profile", {app_name = APP_NAME, app_version = VERSION}, c.token)
    if not current(c) then return "连接配置已更改，请重新测试" end
    if result.ok ~= true then return "服务端没有确认设备连接" end
    return nil -- Tests HTTP authentication only; WS starts on the next visible-keyboard pull.
end

function plugin.push(profile)
    if type(profile) ~= "table" or profile.type ~= "text" or type(profile.text) ~= "string"
        or profile.text:match("^%s*$") or profile.text:find("%z") then error("只同步非空纯文本") end
    local c = connection()
    pending = nil -- A new local copy supersedes remote text waiting for the next pull.
    -- The current protocol has no idempotency key. Never let the host blindly replay an
    -- uncertain upload: report it in plugin errors and consume this copy event. A new copy retries.
    local ok, result = pcall(post, c.base, "items/text", {upload_kind = "realtime",
        text_content = profile.text}, c.token)
    if not ok then
        host.logError("Clipboard River 文本上传失败，本次复制未确认送达；为避免重复未自动重发，请检查连接后重新复制。")
        return true
    end
    if not current(c) then error("连接配置已更改，未确认上传") end
    local item = result.item
    if result.created ~= true or type(item) ~= "table" or type(item.item_id) ~= "number" or item.item_id <= 0
        or item.content_kind ~= "text" or item.upload_kind ~= "realtime" then
        host.logError("Clipboard River 上传回执无效，本次复制未确认送达；为避免重复未自动重发。")
    end
    return true
end

function plugin.pull()
    local c = connection()
    ensureSocket(c)
    return pending
end

function plugin.acknowledgePull(hash)
    connection()
    if not pending or pending.hash ~= hash then return end
    lastAppliedId = pending.itemId
    pending = nil
    -- Host acceptance is local bookkeeping; the current server protocol has no ack message.
end

return plugin
