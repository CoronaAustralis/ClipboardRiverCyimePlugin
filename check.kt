// Standalone protocol checks: LuaJ 3.0.1 and JSON-java. No CyIME checkout or live credentials.
import org.json.JSONObject
import org.luaj.vm2.*
import org.luaj.vm2.lib.VarArgFunction
import org.luaj.vm2.lib.jse.JsePlatform
import java.io.File
import java.security.MessageDigest
import java.util.UUID

private fun lua(value: Any?): LuaValue = when (value) {
    null -> LuaValue.NIL
    is Map<*, *> -> LuaTable().also { table -> value.forEach { (k, v) -> table.set(k.toString(), lua(v)) } }
    is List<*> -> LuaTable().also { table -> value.forEachIndexed { i, v -> table.set(i + 1, lua(v)) } }
    is String -> LuaValue.valueOf(value)
    is Boolean -> LuaValue.valueOf(value)
    is Number -> LuaValue.valueOf(value.toDouble())
    else -> error("Unsupported test value")
}

// Same conversion order as LuaScriptRuntime.tableToJava, including numeric string coercion.
private fun java(value: LuaValue): Any? = when {
    value.isnil() -> null
    value.isboolean() -> value.toboolean()
    value.isnumber() -> if (value.isint()) value.toint() else value.todouble()
    value.isstring() -> value.tojstring()
    value.istable() -> value.checktable().keys().associate { it.tojstring() to java(value.get(it)) }
    else -> error("Unsupported Lua test value")
}

private fun bytes(value: LuaValue): ByteArray = value.checkstring().let {
    ByteArray(it.m_length).also { out -> it.copyInto(0, out, 0, out.size) }
}
private fun function(body: (Varargs) -> LuaValue) = object : VarArgFunction() {
    override fun invoke(args: Varargs): Varargs = body(args)
}
private fun json(value: Any?) = JSONObject.wrap(value).toString()
private fun parse(value: String): Map<*, *> = JSONObject(value).toMap()

private class Harness(script: File) {
    val config = mutableMapOf("serverUrl" to "https://river.example", "enrollmentCode" to "123456")
    data class Request(val url: String, val headers: LuaValue, val body: Map<*, *>, val raw: ByteArray)
    val requests = mutableListOf<Request>()
    val sent = mutableListOf<Map<*, *>>()
    val logs = mutableListOf<String>()
    var http: (Request) -> LuaValue = { response(mapOf("device_id" to 7, "device_token" to "12345")) }
    var callbacks: LuaValue = LuaValue.NIL
    var state = 0
    var connects = 0
    var closes = 0
    var now = 1000L
    var socketUrl = ""
    var acceptSocket = true
    val plugin: LuaValue

    init {
        val globals = JsePlatform.standardGlobals()
        val host = LuaTable()
        host.set("config", LuaTable().also { cfg ->
            cfg.set("get", function { lua(config[it.arg1().checkjstring()]) })
            cfg.set("set", function { config[it.arg1().checkjstring()] = it.arg(2).checkjstring(); LuaValue.TRUE })
        })
        host.set("json", LuaTable().also { api ->
            api.set("encode", function { lua(json(java(it.arg1()))) })
            api.set("decode", function { lua(parse(it.arg1().checkjstring())) })
        })
        host.set("bin", LuaTable().also { api ->
            api.set("utf8", function { LuaString.valueOf(it.arg1().checkjstring().toByteArray(Charsets.UTF_8)) })
        })
        host.set("crypto", LuaTable().also { api ->
            api.set("epochSeconds", function { lua(now) })
            api.set("sha256", function { LuaString.valueOf(MessageDigest.getInstance("SHA-256").digest(bytes(it.arg1()))) })
            api.set("hex", function { lua(bytes(it.arg1()).joinToString("") { byte -> "%02x".format(byte) }) })
        })
        host.set("http", LuaTable().also { api ->
            api.set("request", function {
                check(it.arg1().checkjstring() == "POST")
                val raw = bytes(it.arg(4))
                val request = Request(it.arg(2).checkjstring(), it.arg(3), parse(raw.toString(Charsets.UTF_8)), raw)
                requests += request
                http(request)
            })
        })
        host.set("ws", LuaTable().also { api ->
            api.set("connect", function {
                connects++
                socketUrl = it.arg1().checkjstring()
                callbacks = it.arg(3)
                state = if (acceptSocket) 1 else 3
                lua(acceptSocket)
            })
            api.set("sendText", function { sent += parse(it.arg1().checkjstring()); LuaValue.NIL })
            api.set("close", function { state = 3; closes++; LuaValue.NIL })
            api.set("getState", function { lua(state) })
        })
        host.set("uuid", function { lua(UUID.randomUUID().toString()) })
        host.set("logError", function { logs += it.arg1().checkjstring(); LuaValue.NIL })
        globals.set("host", host)
        // Mirror the host's unavailable ambient file/network/system APIs.
        for (key in listOf("io", "os", "luajava", "loadfile", "dofile", "require", "package")) globals.set(key, LuaValue.NIL)
        plugin = globals.load(script.readText(Charsets.UTF_8), "main.lua").call()
        check(call("onLoad").toboolean())
    }

    fun call(name: String, argument: LuaValue = LuaValue.NIL): LuaValue = plugin.get(name).call(argument)
    fun register() { check(call("registerDevice").isnil()) }
    fun open() { state = 2; callbacks.get("onOpen").call() }
    fun receive(message: Map<String, Any?>) { callbacks.get("onMessage").call(lua(json(message))) }
    fun online() {
        check(call("pull").isnil())
        open()
        receive(mapOf("type" to "hello_ack", "payload" to mapOf("device_id" to 7)))
    }
    fun event(text: String, itemId: Int, kind: String = "text", upload: String = "realtime", source: Int = 9) =
        mapOf("type" to "clipboard.created", "item" to mapOf("content_kind" to kind, "upload_kind" to upload,
            "source_device_id" to source, "item_id" to itemId, "text_content" to text))
    fun push(text: String) = call("push", lua(mapOf("type" to "text", "text" to text)))
    fun uploadResponse(itemId: Int = 50) = response(mapOf("created" to true,
        "item" to mapOf("item_id" to itemId, "content_kind" to "text", "upload_kind" to "realtime",
            "mime_type" to "", "received_at" to "2026-11-14T09:00:00Z")))
}

private fun response(body: Any?, status: Int = 200) = lua(mapOf("status" to status, "text" to json(body)))
private fun fails(block: () -> Unit) {
    var failed = false
    try { block() } catch (_: LuaError) { failed = true }
    check(failed) { "Expected a reported Lua failure" }
}

fun main(args: Array<String>) {
    val script = File(args.single())
    var passed = 0
    fun test(name: String, body: (Harness) -> Unit) {
        body(Harness(script)); passed++; println("PASS $name")
    }

    test("independent schema; no implicit registration; stable identity and numeric credentials") { h ->
        check(h.requests.isEmpty() && h.connects == 0)
        check((1..4).map { h.call("getSettingsSchema").get(it).get("key").checkjstring() } ==
            listOf("serverUrl", "enrollmentCode", "registerDevice", "testConnection"))
        h.register()
        val first = h.requests.single()
        check(first.url == "https://river.example/api/v1/client/register")
        check(first.body["enrollment_code"] == "123456")
        check(first.body.keys == setOf("device_uuid", "enrollment_code", "nickname", "app_name", "app_version"))
        check(first.body["app_name"] == "Clipboard River CyIME")
        check(parse(h.config.getValue("registration"))["device_token"] == "12345")
        h.register()
        check(h.requests.last().body["device_uuid"] == first.body["device_uuid"])
        h.http = { response(mapOf("ok" to true)) }
        check(h.call("testConnection").isnil())
        check(h.requests.last().url.endsWith("/device/profile"))
        check(h.requests.last().body.keys == setOf("app_name", "app_version"))
        check(h.requests.last().headers.get("Authorization").checkjstring() == "Bearer 12345")
    }

    test("compact upload receipt, allowed request fields, UTF-8 and numeric text") { h ->
        h.register()
        val text = "中文 😀\r\n第二行\t\"引号\"\\路径"
        h.http = { h.uploadResponse() }
        check(h.push(text).toboolean())
        check(h.logs.isEmpty())
        check(h.requests.last().body.keys == setOf("upload_kind", "text_content"))
        check(h.requests.last().body["text_content"] == text)
        check(h.requests.last().body["upload_kind"] == "realtime")
        check(!h.requests.last().body.containsKey("client_created_at"))
        listOf("123", "00123", "1e5", "-42", "0", "\u0001").forEach {
            h.push(it); check(h.requests.last().body["text_content"] == it)
        }
        check(h.logs.isEmpty())
    }

    test("WS hello contains only current fields; acceptance sends no legacy ack") { h ->
        h.register(); h.online()
        check(h.socketUrl == "wss://river.example/api/v1/client/ws")
        val hello = h.sent.single()
        check(hello["type"] == "hello")
        check((hello["payload"] as Map<*, *>)["device_token"] == "12345")
        check((hello["payload"] as Map<*, *>).keys == setOf("device_token", "device_uuid"))
        val text = "电脑 😀\n下一行"
        h.receive(h.event(text, 12))
        val profile = h.call("pull")
        check(profile.get("text").checkjstring() == text)
        val utf8 = text.toByteArray(Charsets.UTF_8)
        check(profile.get("size").toint() == utf8.size)
        check(profile.get("hash").checkjstring() == MessageDigest.getInstance("SHA-256")
            .digest(utf8).joinToString("") { "%02x".format(it) })
        check(h.call("pull") == profile && h.sent.size == 1)
        h.call("acknowledgePull", lua("wrong-hash"))
        check(h.sent.size == 1)
        h.call("acknowledgePull", profile.get("hash"))
        check(h.call("pull").isnil())
        check(h.sent.size == 1)
    }

    test("ignore files, history, own events, duplicates, malformed and blank text") { h ->
        h.register(); h.online()
        h.receive(h.event("accepted", 10))
        val profile = h.call("pull")
        h.call("acknowledgePull", profile.get("hash"))
        listOf(h.event("duplicate", 10), h.event("file", 11, kind = "file"),
            h.event("history", 12, upload = "history"), h.event("own", 13, source = 7),
            h.event(" \r\n\t", 14), h.event("x\u0000y", 15), mapOf("type" to "clipboard.created"),
            mapOf("type" to "clipboard.sync_changed", "payload" to mapOf("item_ids" to listOf(15, 9))))
            .forEach { h.receive(it); check(h.call("pull").isnil()) }
        h.callbacks.get("onMessage").call(lua("not JSON"))
        check(h.call("pull").isnil())
    }

    test("last arriving text wins; database item IDs are not synchronization cursors") { h ->
        h.register(); h.online()
        h.receive(h.event("first", 12)); h.receive(h.event("last arrival", 10))
        check(h.call("pull").get("text").checkjstring() == "last arrival")
        h.call("acknowledgePull", h.call("pull").get("hash"))
        h.receive(h.event("later with a lower ID", 9))
        check(h.call("pull").get("text").checkjstring() == "later with a lower ID")
        h.http = { h.uploadResponse(20) }; h.push("new local copy")
        check(h.call("pull").isnil())
        h.receive(h.event("next remote", 19)); check(h.call("pull").get("text").checkjstring() == "next remote")
    }

    test("reconnect backs off and resumes hello without requesting history") { h ->
        h.register(); h.online()
        h.state = 3; h.callbacks.get("onError").call(lua("secret server payload"))
        h.call("pull"); check(h.connects == 1)
        h.now += 2; h.call("pull"); check(h.connects == 2)
        h.open(); check(h.sent.last()["type"] == "hello")
        check(h.logs.none { it.contains("secret") || it.contains("12345") })
        check(h.requests.all { it.url.endsWith("register") })
    }

    test("pending reception survives a reconnect and unloading closes the connection") { h ->
        h.register(); h.online(); h.receive(h.event("received while waiting for host", 10))
        h.state = 3; h.callbacks.get("onClose").call()
        check(h.call("pull").get("text").checkjstring() == "received while waiting for host")
        val count = h.closes; h.call("onUnload"); check(h.closes == count + 1 && h.state == 3)
    }

    test("wrong hello and timeout never count as an authenticated connection") { h ->
        h.register(); h.call("pull"); h.open()
        h.receive(mapOf("type" to "hello_ack", "payload" to mapOf("device_id" to 999)))
        h.receive(h.event("must not apply", 10)); check(h.call("pull").isnil())
        h.now += 15; h.call("pull"); check(h.state == 3)
        h.now += 2; h.call("pull"); check(h.connects == 2)
    }

    test("server changes never leak old credentials and stale WS messages are ignored") { h ->
        h.register(); h.online(); val oldCallbacks = h.callbacks
        h.config["serverUrl"] = "http://new.example/"
        val count = h.requests.size
        oldCallbacks.get("onMessage").call(lua(json(h.event("old server", 99))))
        fails { h.call("pull") }; fails { h.push("do not send old token") }
        check(h.requests.size == count)
        h.register(); h.online()
        check(h.socketUrl == "ws://new.example/api/v1/client/ws")
        oldCallbacks.get("onMessage").call(lua(json(h.event("old server", 100))))
        check(h.call("pull").isnil())
    }

    test("reverse proxy paths reach registration, profile, upload and WebSocket") { h ->
        for (base in listOf("https://river.example/river", "http://river.example:8080/api",
            "https://river.example/nested/river", "https://river.example/tenant%20name")) {
            h.config["serverUrl"] = "  $base///  "
            h.http = { response(mapOf("device_id" to 7, "device_token" to "12345")) }
            h.register()
            check(h.requests.last().url == "$base/api/v1/client/register")
            check(parse(h.config.getValue("registration"))["server"] == base)
            h.http = { response(mapOf("ok" to true)) }
            check(h.call("testConnection").isnil())
            check(h.requests.last().url == "$base/api/v1/client/device/profile")
            h.http = { h.uploadResponse() }; h.push("prefix upload")
            check(h.requests.last().url == "$base/api/v1/client/items/text")
            h.online()
            check(h.socketUrl == base.replaceFirst("http", "ws") + "/api/v1/client/ws")
            h.receive(h.event("prefix received", 100))
            check(h.call("pull").get("text").checkjstring() == "prefix received")
            // A sibling path on the same host is a different service and must not get this token.
            h.config["serverUrl"] = "$base/other"
            val count = h.requests.size
            fails { h.push("wrong service") }
            check(h.requests.size == count)
        }
    }

    test("configuration and HTTP failures surface without silent success") { h ->
        listOf("", "file:///x", "http://user:pass@example.com", "https://example.com/path with spaces",
            "https://example.com?token=x", "https://example.com\\evil").forEach {
            h.config["serverUrl"] = it; fails { h.register() }
        }
        check(h.requests.isEmpty())
        h.config["serverUrl"] = "https://river.example"; h.register()
        for (status in listOf(400, 401, 403, 500)) {
            h.http = { response(emptyMap<String, Any>(), status) }; fails { h.call("testConnection") }
        }
        h.http = { lua(mapOf("status" to 200, "text" to "<html>captive portal</html>")) }
        fails { h.call("testConnection") }
        h.http = { LuaValue.NIL }; check(h.push("offline").toboolean())
        check(h.logs.last().contains("未确认送达"))
        val count = h.requests.size
        fails { h.push(" \n") }; fails { h.push("x\u0000y") }
        check(h.requests.size == count)
    }

    test("registration errors retain the server reason, including a closed enrollment window") { h ->
        h.http = { response(mapOf("error" to "enrollment closed"), 400) }
        try {
            h.register(); error("Expected a rejected registration")
        } catch (failure: LuaError) {
            check(failure.message.orEmpty().contains("enrollment closed"))
            check(failure.message.orEmpty().contains("HTTP 400"))
        }
        check("registration" !in h.config)
    }

    test("failed or uncertain uploads are consumed with an error, preventing host automatic replay") { h ->
        h.register()
        for (result in listOf(LuaValue.NIL, response(mapOf("error" to "server unavailable"), 503),
            response(mapOf("error" to "invalid request"), 400), response(mapOf("created" to true)))) {
            h.http = { result }
            val before = h.requests.size
            val errors = h.logs.size
            // ClipboardSyncSession consumes the pending copy only when push returns true.
            check(h.push("uncertain upload").toboolean())
            check(h.requests.size == before + 1 && h.logs.size == errors + 1)
            check(h.logs.last().contains("未自动重发"))
        }
        // The user can explicitly retry by copying again, even with exactly the same text.
        h.http = { h.uploadResponse() }
        val before = h.requests.size
        check(h.push("uncertain upload").toboolean())
        check(h.requests.size == before + 1)
    }

    println("$passed protocol checks passed (LuaJ 3.0.1; mocked HTTP/WS; Android not exercised).")
}
