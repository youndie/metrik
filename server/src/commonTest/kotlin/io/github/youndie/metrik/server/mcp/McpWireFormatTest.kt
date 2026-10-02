package io.github.youndie.metrik.server.mcp

import io.github.youndie.kore.mcp.KoreMcpConfig
import io.github.youndie.kore.mcp.installKoreMcp
import io.github.youndie.metrik.server.ServerConfig
import io.github.youndie.metrik.server.TestDatabase
import io.github.youndie.metrik.server.alert.AlertWorker
import io.github.youndie.metrik.server.alert.NoopNotifier
import io.github.youndie.metrik.server.module
import io.github.youndie.metrik.server.query.AdminService
import io.github.youndie.metrik.server.query.QueryService
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.DefaultJson
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStatelessStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import io.modelcontextprotocol.kotlin.sdk.types.LATEST_PROTOCOL_VERSION
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

/**
 * Предупреждение, которое metrik пишет при старте: `ContentNegotiation is already installed. MCP
 * requires json(McpJson)…`. Его печатает SDK 0.15.0 всякий раз, когда находит ContentNegotiation
 * приложения: какой у того `Json`, публичный API Ktor не показывает, поэтому SDK предупреждает
 * вслепую. Вопрос за предупреждением — не уходят ли сообщения MCP в JSON metrik вместо JSON
 * протокола, — проверяется здесь по сырому телу ответа.
 *
 * Через ContentNegotiation приложения у stateless-транспорта SDK может пройти ровно одно — ответ
 * на POST в JSON-режиме (`call.respond(payload)`). Запрос SDK читает сам, сырым телом, и разбирает
 * своим `McpJson`; отказы (`reject`, проверка `Host`) пишет сам текстом. А этот единственный ответ
 * kore-mcp кодирует `McpJson` на маршруте транспорта, до того как его увидит ContentNegotiation.
 *
 * Мерило — [mismatch]: тело ответа обязано быть ровно тем, что `McpJson` пишет для сообщения, которое
 * в нём лежит. Вредный `Json` это равенство ломает: выпавшее поле со значением по умолчанию, лишний
 * `null`, другие пробелы.
 */
class McpWireFormatTest {
    private val database = TestDatabase("mcp-wire")
    private val db = database.db

    @AfterTest
    fun cleanup() = database.close()

    private fun initialize(version: String) =
        """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"$version",""" +
            """"capabilities":{},"clientInfo":{"name":"test","version":"1"}}}"""

    /**
     * Запросы, на которые транспорт отвечает сообщением: `initialize` с последней версией протокола
     * (она же значение поля по умолчанию) и со старой, `ping` с ключом, которого нет в протоколе,
     * список инструментов, вызов без необязательных полей, вызов, закончившийся ошибкой инструмента,
     * ошибка JSON-RPC, пакет из двух запросов и тело, которое не разбирается вовсе.
     *
     * `ping` с чужим ключом — проверка стороны запроса: под [OMITS_DEFAULTS] чужие ключи запрещены,
     * и его `200` значит, что запрос разбирал не `Json` приложения.
     */
    private val exchanges =
        listOf(
            Exchange("initialize latest", initialize(LATEST_PROTOCOL_VERSION)),
            Exchange("initialize older", initialize("2025-06-18")),
            Exchange("ping with a key the protocol does not define", PING_WITH_UNKNOWN_KEY),
            Exchange("tools/list", """{"jsonrpc":"2.0","id":4,"method":"tools/list"}"""),
            Exchange(
                "tools/call without arguments",
                """{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"list_services"}}""",
            ),
            Exchange(
                "tools/call failing without the optional step",
                """{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"time_series",""" +
                    """"arguments":{"service":"nope","from":0,"to":1}}}""",
            ),
            Exchange(
                "tools/call of a tool that does not exist",
                """{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"no_such_tool"}}""",
            ),
            Exchange("method not found", """{"jsonrpc":"2.0","id":8,"method":"no/such/method"}"""),
            Exchange(
                "batch",
                """[{"jsonrpc":"2.0","id":9,"method":"ping"},{"jsonrpc":"2.0","id":10,"method":"tools/list"}]""",
            ),
            Exchange("unparseable body", """{"jsonrpc":"2.0","id":""", HttpStatusCode.BadRequest),
        )

    @Test
    fun `every MCP answer of the assembled server should be exactly what McpJson writes`() =
        testApplication {
            // Given — собранный `module`: ContentNegotiation на `MetrikJson` стоит раньше
            // `installKoreMcp`, как в проде, — ровно то состояние, в котором SDK предупреждает.
            application {
                module(
                    ServerConfig(
                        httpPort = 0,
                        udpPort = 0,
                        dbPath = database.path,
                        ingestKey = "key",
                        mcpToken = TOKEN,
                    ),
                    db,
                )
            }

            // When
            val mismatches = mismatches()

            // Then
            assertEquals(emptyMap(), mismatches)
        }

    @Test
    fun `an application Json that omits defaults should not reach the answers through kore-mcp`() =
        testApplication {
            // Given — умолчания kotlinx: `encodeDefaults = false`, чужие ключи запрещены.
            mcp(OMITS_DEFAULTS, throughKore = true)

            // When
            val mismatches = mismatches()

            // Then
            assertEquals(emptyMap(), mismatches)
        }

    @Test
    fun `an application Json that writes nulls should not reach the answers through kore-mcp`() =
        testApplication {
            // Given — то, что даёт `json()` без аргумента: умолчания пишутся, `null` — тоже.
            mcp(DefaultJson, throughKore = true)

            // When
            val mismatches = mismatches()

            // Then
            assertEquals(emptyMap(), mismatches)
        }

    /**
     * ПОЗИТИВНЫЙ КОНТРОЛЬ к двум тестам выше: те же инструменты, те же запросы и тот же `Json`, но
     * транспорт поставлен голым SDK, без kore-mcp, — и ответ уходит через ContentNegotiation
     * приложения. Без него зелёный цвет выше мог бы значить «мерило ничего не ловит».
     *
     * Что при этом ломается (замер на SDK 0.15.0): `Json` без умолчаний выбрасывает
     * `protocolVersion` из ответа на `initialize` с последней версией и `result` целиком из ответа на
     * `ping` — ответ без `result` и без `error` уже не JSON-RPC.
     *
     * **Покраснел — значит, SDK больше не отдаёт свой ответ ContentNegotiation приложения.** Тогда
     * пропадёт и предупреждение при старте; удалить оба контроля и абзац о предупреждении в
     * `docs/api/mcp-tools.md`.
     */
    @Test
    fun `control - the bare SDK should answer through an application Json that omits defaults`() =
        testApplication {
            // Given
            mcp(OMITS_DEFAULTS, throughKore = false)

            // When
            val mismatches = mismatches()

            // Then
            assertTrue(mismatches.isNotEmpty(), "the SDK no longer answers through ContentNegotiation - see the KDoc")
        }

    /** То же для `json()` без аргумента: на SDK 0.15.0 в каждый ответ-сообщение добавляются `null` незаданных полей. */
    @Test
    fun `control - the bare SDK should answer through an application Json that writes nulls`() =
        testApplication {
            // Given
            mcp(DefaultJson, throughKore = false)

            // When
            val mismatches = mismatches()

            // Then
            assertTrue(mismatches.isNotEmpty(), "the SDK no longer answers through ContentNegotiation - see the KDoc")
        }

    @Test
    fun `the request with an unknown key should be one the strict Json refuses`() {
        // Контроль к `ping with a key the protocol does not define`: его `200` под `OMITS_DEFAULTS`
        // значит, что запрос разбирал не этот `Json`, только если этот `Json` его не разбирает.
        assertFails { OMITS_DEFAULTS.decodeFromString(message, PING_WITH_UNKNOWN_KEY) }
    }

    /** Приложение с ContentNegotiation на [json] и инструментами metrik — через kore-mcp или голым SDK. */
    private fun ApplicationTestBuilder.mcp(
        json: Json,
        throughKore: Boolean,
    ) {
        application {
            install(ContentNegotiation) { json(json) }
            val admin = AdminService(db)
            val facade = ToolFacade(QueryService(db), AlertWorker(db, admin, NoopNotifier), admin)
            if (throughKore) {
                installKoreMcp(KoreMcpConfig(TOKEN), INFO) { registerTools(facade) }
            } else {
                bareSdk(facade)
            }
        }
    }

    private fun Application.bareSdk(facade: ToolFacade) {
        mcpStatelessStreamableHttp(path = "/mcp", enableDnsRebindingProtection = false) {
            Server(INFO, ServerOptions(ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))))
                .apply { registerTools(facade) }
        }
    }

    /** Каждый обмен из [exchanges], у которого тело не совпало с `McpJson`, — с тем, чем не совпало. */
    private suspend fun ApplicationTestBuilder.mismatches(): Map<String, String> =
        exchanges
            .mapNotNull { exchange ->
                val response =
                    client.post("/mcp") {
                        header(HttpHeaders.Authorization, "Bearer $TOKEN")
                        header(HttpHeaders.Accept, "application/json, text/event-stream")
                        contentType(ContentType.Application.Json)
                        setBody(exchange.request)
                    }
                val body = response.bodyAsText()
                assertEquals(exchange.status, response.status, "${exchange.name}: $body")
                mismatch(body)?.let { exchange.name to it }
            }.toMap()

    private class Exchange(
        val name: String,
        val request: String,
        val status: HttpStatusCode = HttpStatusCode.OK,
    )

    private companion object {
        const val TOKEN = "secret"
        val INFO = Implementation(name = "metrik", version = "0.1")

        const val PING_WITH_UNKNOWN_KEY = """{"jsonrpc":"2.0","id":3,"method":"ping","x-unknown":true}"""

        /** Умолчания kotlinx — `Json` без настройки. */
        val OMITS_DEFAULTS: Json = Json

        val message = serializer<JSONRPCMessage>()
        val batch = ListSerializer(message)

        /**
         * `null`, если [body] — ровно то, что `McpJson` пишет для сообщения (или пакета), которое в
         * нём лежит; иначе — чем не совпало. Разбор и запись — `McpJson`: выпавшее поле он вернёт
         * умолчанием, лишний `null` и чужой ключ отбросит, пробелов не поставит, и байты разойдутся;
         * а тело, из которого выпало то, по чему узнаётся тип сообщения, он не разберёт вовсе.
         */
        fun mismatch(body: String): String? {
            val rewritten =
                runCatching {
                    if (body.startsWith("[")) {
                        McpJson.encodeToString(batch, McpJson.decodeFromString(batch, body))
                    } else {
                        McpJson.encodeToString(message, McpJson.decodeFromString(message, body))
                    }
                }.getOrElse { return "not a JSON-RPC message: ${it.message}" }
            return if (rewritten == body) null else "McpJson writes $rewritten, got $body"
        }
    }
}
