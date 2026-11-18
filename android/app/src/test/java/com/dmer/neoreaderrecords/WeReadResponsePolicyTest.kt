package com.dmer.neoreaderrecords

import org.json.JSONObject
import org.junit.Assert.assertThrows
import org.junit.Test

class WeReadResponsePolicyTest {
    private val fixtures = mapOf(
        "/shelf/sync" to """{"books":[],"albums":[]}""",
        "/readdata/detail" to """{"totalReadTime":0,"readTimes":{}}""",
        "/book/getprogress" to """{"book":{"progress":0}}""",
        "/book/bookmarklist" to """{"updated":[]}""",
        "/review/list/mine" to """{"reviews":[]}""",
        "/book/bestbookmarks" to """{"items":[]}"""
    )

    @Test fun acceptsBusinessDataWithOrWithoutUpgradeNotice() {
        fixtures.forEach { (api, body) ->
            WeReadResponsePolicy.validate(api, JSONObject(body))
            val json = JSONObject(body).put("upgrade_info", JSONObject().put("version", "99.0.0"))
            WeReadResponsePolicy.validate(api, json)
            json.put("upgrade_info", "通知字段形态变化也不应阻断合法业务数据")
            WeReadResponsePolicy.validate(api, json)
        }
    }

    @Test fun rejectsActualErrorsEvenWithValidBusinessAndNotice() {
        fixtures.forEach { (api, body) ->
            val json = JSONObject(body).put("errcode", -2010).put("upgrade_info", JSONObject())
            assertThrows(IllegalStateException::class.java) { WeReadResponsePolicy.validate(api, json) }
        }
    }

    @Test fun rejectsUpgradeOnlyInsteadOfReturningEmptySuccess() {
        fixtures.keys.forEach { api ->
            assertThrows(IllegalArgumentException::class.java) {
                WeReadResponsePolicy.validate(api, JSONObject("""{"errcode":0,"upgrade_info":{}}"""))
            }
        }
    }

    @Test fun rejectsWrongFieldTypesAndMalformedErrorCode() {
        listOf(
            "/shelf/sync" to """{"books":{}}""",
            "/readdata/detail" to """{"totalReadTime":"100","readTimes":{}}""",
            "/readdata/detail" to """{"totalReadTime":100,"readTimes":[]}""",
            "/book/getprogress" to """{"book":[]}""",
            "/shelf/sync" to """{"books":[],"errcode":"0"}"""
        ).forEach { (api, body) ->
            assertThrows(IllegalArgumentException::class.java) { WeReadResponsePolicy.validate(api, JSONObject(body)) }
        }
    }
}
