package kz.tungo.sdk.kotlin

/** JSON bodies shaped like the answers of the API. */
internal object Fixtures {
    const val COMPANY: String =
        """{"id":"co_1","name":"ТОО Ромашка","bin":"990140000385","created_at":"2026-01-02T03:04:05Z","legal_form":"too",""" +
            """"permissions":["chat.use","a.permission.from.the.future"],"role":"owner","role_name":"Владелец","status":"active",""" +
            """"workspace_id":"ws_1","a_field_from_the_future":{"x":[1,2,3]}}"""

    fun dialog(id: String): String =
        """{"id":"$id","message_count":3,"running":false,"title":"Разговор $id","updated_at":"2026-01-02T03:04:05Z"}"""

    fun dialogs(next: String?, vararg ids: String): String =
        """{"data":[${ids.joinToString(",") { dialog(it) }}],"next_cursor":${if (next == null) "null" else "\"$next\""}}"""

    fun endpoint(channels: String = "null"): String =
        """{"id":"ep_1","url":"https://example.com/hook","description":"","enabled":true,"channels":$channels,"event_types":null,""" +
            """"created_at":"2026-01-02T03:04:05Z","updated_at":"2026-01-02T03:04:05Z"}"""

    fun run(state: String): String =
        """{"id":"run_1","attempt":1,"auto_retries":0,"cancel_requested":false,"created_at":"2026-01-02T03:04:05Z","proposals":[],""" +
            """"retryable":false,"scenario_id":"sc_1","state":"$state","steps":[],"tools":[],"trial":false,"trigger":"manual",""" +
            """"answer":null,"output":{"total":12.5,"ok":true,"note":"текст"},"cost_tiyn":9007199254740993}"""

    fun batch(running: Boolean): String =
        """{"id":"b_1","kind":"approve","running":$running,"started_at":"2026-01-02T03:04:05Z","title":"Пачка","events":4}"""

    const val TOKEN_COUNT: String =
        """{"input_cost_tiyn":9007199254740993,"input_tokens":1200,"max_cost_tiyn":9223372036854775807,"max_output_tokens":4096,"model":"m"}"""

    const val TURN: String =
        """{"answer":"Готово","citations":[],"dialog_id":"d_1","errors":[],"pending_question":null,"proposals":[],"questions":[],""" +
            """"stop_reason":"end_turn","turn_no":2}"""

    const val HOOK_ACCEPTED: String = """{"accepted":true,"duplicate":false,"event_id":"evt_1","run_ids":["run_1"]}"""

    const val SSE_TWO_TEXTS: String =
        "id: 1\nevent: text\ndata: {\"type\":\"text\",\"delta\":\"Привет, \",\"step\":1}\n\n" +
            "event: ping\ndata:\n\n" +
            "id: 2\nevent: text\ndata: {\"type\":\"text\",\"delta\":\"мир\",\"step\":1}\n\n" +
            "id: 3\nevent: brand_new\ndata: {\"type\":\"brand_new\",\"payload\":{\"n\":1}}\n\n" +
            "id: 4\nevent: done\ndata: {\"type\":\"done\",\"stop_reason\":\"end_turn\",\"cost_tiyn\":185}\n\n"

    fun error(type: String, message: String, code: String? = null): String =
        """{"error":{"type":"$type","message":"$message"${if (code == null) "" else ",\"code\":\"$code\""}}}"""
}
