package com.dmer.neoreaderrecords

import org.json.JSONArray
import org.json.JSONObject

/** 官方1.0.6将upgrade_info定义为非阻塞通知，业务契约独立校验。 */
internal object WeReadResponsePolicy {
    fun validate(api: String, json: JSONObject) {
        val errCode = json.opt("errcode")
        require(errCode == null || errCode is Number) { "微信接口错误码格式变化，请更新 App" }
        if (errCode is Number && errCode.toLong() != 0L) {
            error("微信接口错误 errcode=${errCode.toLong()}；请检查 Key 或接口状态")
        }
        val valid = when (api) {
            "/shelf/sync" -> json.opt("books") is JSONArray
            "/readdata/detail" -> json.opt("totalReadTime") is Number && json.opt("readTimes") is JSONObject
            "/book/getprogress" -> json.opt("book") is JSONObject
            "/book/bookmarklist" -> json.opt("updated") is JSONArray
            "/review/list/mine" -> json.opt("reviews") is JSONArray
            "/book/bestbookmarks" -> json.opt("items") is JSONArray
            else -> false
        }
        require(valid) {
            "微信接口未返回可识别的业务数据（$api），请检查接口状态或更新 App；已有数据未清除"
        }
        // 不下载、不执行upgrade_url，不改写skill_version，也不额外重试。
    }
}
