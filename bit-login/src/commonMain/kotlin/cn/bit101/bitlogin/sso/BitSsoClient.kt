package cn.bit101.bitlogin.sso

import io.ktor.http.HttpMethod
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import cn.bit101.bitlogin.http.HttpClient
import cn.bit101.bitlogin.http.HttpResponse
import cn.bit101.bitlogin.util.PythonUrlEncoding
import cn.bit101.bitlogin.util.base64Decode
import cn.bit101.bitlogin.util.currentTimeMillis
import cn.bit101.bitlogin.util.uriHost
import cn.bit101.bitlogin.util.uriPath
import cn.bit101.bitlogin.util.uriQuery
import cn.bit101.bitlogin.util.uriResolve

interface SsoTransport {
    suspend fun request(request: SsoRequest): HttpResponse
    suspend fun requestBytes(request: SsoRequest): ByteArray = request(request).bodyBytes ?: throw ConfigurationError("transport did not return binary data")
    fun cookieValue(name: String): String = ""
    fun cookieMap(): Map<String, String> = emptyMap()
    suspend fun setCookie(name: String, value: String, domain: String) {}
}

data class SsoRequest(
    val method: HttpMethod,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val query: Map<String, String> = emptyMap(),
    val form: Map<String, String>? = null,
    val json: JsonObject? = null,
    val rawBody: String? = null,
    val allowRedirects: Boolean = true,
)

class BitSsoClient(
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val transport: SsoTransport,
    private val captchaSolver: CaptchaSolver? = null,
    private val riskTokenProvider: RiskTokenProvider? = null,
    private val fingerprintProfile: BrowserFingerprintProfile = BrowserFingerprintProfile(),
    private val userAgent: String = DEFAULT_USER_AGENT,
) {
    constructor(
        session: HttpClient = HttpClient(),
        baseUrl: String = DEFAULT_BASE_URL,
        captchaSolver: CaptchaSolver? = null,
        riskTokenProvider: RiskTokenProvider? = null,
    ) : this(baseUrl, HttpClientTransport(session), captchaSolver, riskTokenProvider)

    private val normalizedBaseUrl = baseUrl.trimEnd('/')
    private val casUrl = "$normalizedBaseUrl/cas"
    private val gateUrl = "$normalizedBaseUrl/gate"
    private var loginReferer = "$casUrl/login"
    private var lastExecution = ""
    var lastCaptchaRequired: Boolean = false
        private set
    var lastRiskMode: String = "not-required"
        private set

    suspend fun loginPassword(
        username: String,
        password: String,
        service: String? = null,
        smsCodeCallback: SmsCodeCallback? = null,
        captchaSolver: CaptchaSolver? = null,
        trustDevice: Boolean = false,
        followRedirects: Boolean = true,
    ): SsoLoginResult {
        require(username.isNotBlank() && password.isNotEmpty()) { "username and password must not be empty" }
        val cleanUsername = username.trim()
        require(cleanUsername.isNotEmpty()) { "username must not contain only whitespace" }
        lastRiskMode = "not-required"
        lastCaptchaRequired = false

        val loaded = request(
            HttpMethod.Get,
            "$casUrl/login",
            query = service?.let { mapOf("service" to it) }.orEmpty(),
            allowRedirects = followRedirects,
            cacheBust = false,
        )
        val parsed = SsoParser.parseLoginPage(loaded.bodyText, loaded.url)
        val page = parsed.page
        if (page == null) {
            if (findTicket(loaded) != null) return loginResult(loaded)
            val preview = loaded.bodyText.take(300).replace("\n", " ")
            throw ConfigurationError(
                "CAS login page is missing execution or croypto " +
                    "(status=${loaded.status}, url=${loaded.url}, bodyLen=${loaded.bodyText.length}, " +
                    "contentType=${loaded.headers["Content-Type"] ?: "?"}, preview: $preview)"
            )
        }
        loginReferer = loaded.url
        lastExecution = page.execution

        var captchaCode = ""
        var captchaPayload = JsonObject(emptyMap())
        val captchaInfo = requestJson(HttpMethod.Get, "$casUrl/api/protected/user/findCaptchaCount/${encode(cleanUsername)}")
        val captchaData = responseData(captchaInfo)
        if (jsonTruthy(captchaData?.get("captchaInvisible"))) {
            lastCaptchaRequired = true
            val captchaUrl = captchaData!!["captchaUrl"]?.jsonPrimitive?.contentOrNullSafe().orEmpty()
            if (captchaUrl.isEmpty()) throw CaptchaError("the server required a captcha but supplied no image URL")
            val solver = captchaSolver ?: this.captchaSolver
            captchaCode = solveCaptcha(
                captchaImage(captchaUrl),
                CaptchaContext(purpose = "password", username = cleanUsername),
                solver,
            )
            val payload = captchaData["captchaPayload"]
            if (payload is JsonObject) captchaPayload = payload
        }

        val form = commonForm(page, "UsernamePassword", cleanUsername).toMutableMap()
        form["captcha_code"] = captchaCode
        form["password"] = Crypto.encryptAesBase64(password, page.cryptoKey)
        form["captcha_payload"] = Crypto.encryptAesBase64(compactJson(captchaPayload), page.cryptoKey)
        addRiskFields(form, page, "UsernamePassword", cleanUsername)
        // POST /cas/login: do NOT raise on 4xx — the page itself is reused to
        // signal login rejection (400/401/403). loginResult inspects the body
        // and raises LoginError with the proper rejection message instead.
        val response = loginPost(page.formAction, form, followRedirects)
        val secondFactor = SsoParser.parseSecondFactorPage(response.bodyText, response.url)
        if (secondFactor != null) {
            return completeSecondFactor(cleanUsername, secondFactor, smsCodeCallback, trustDevice, followRedirects)
        }
        return loginResult(response, "用户名或密码错误")
    }

    suspend fun loginSms(
        phone: String,
        smsCodeCallback: SmsCodeCallback,
        captchaSolver: CaptchaSolver? = null,
        service: String? = null,
    ): SsoLoginResult {
        if (!PHONE_PATTERN.matches(phone)) {
            throw ConfigurationError("phone must be an 11-digit mainland China mobile number")
        }
        lastRiskMode = "not-required"
        lastCaptchaRequired = true

        val loaded = request(
            HttpMethod.Get,
            "$casUrl/login",
            query = service?.let { mapOf("service" to it) }.orEmpty(),
            cacheBust = false,
        )
        val parsed = SsoParser.parseLoginPage(loaded.bodyText, loaded.url)
        val page = parsed.page ?: throw ConfigurationError("CAS login page is missing execution or croypto")
        loginReferer = loaded.url
        lastExecution = page.execution

        val solver = captchaSolver ?: this.captchaSolver
        val captchaImageBytes = request(
            HttpMethod.Post,
            "$gateUrl/sso-extend/protected/api/aggregate/sms/publicNoToken/generate",
            json = JsonObject(mapOf("phone" to JsonPrimitive(phone), "type" to JsonPrimitive("DEFAULT"))),
        ).bodyBytes ?: throw ConfigurationError("captcha endpoint did not return binary data")
        val captchaCode = solveCaptcha(captchaImageBytes, CaptchaContext(purpose = "sms", phone = phone), solver)
        val encodedCode = PythonUrlEncoding.quote(captchaCode, safe = "")
        val encodedPhone = PythonUrlEncoding.quote(phone, safe = "")
        val sendResult = requestJson(
            HttpMethod.Get,
            "$gateUrl/sso-extend/protected/api/aggregate/sms/publicNoToken/sendCheckCaptcha/DEFAULT/$encodedCode/$encodedPhone/0008",
        )
        val sendCode = responseCode(sendResult)
        if (sendCode != null && sendCode != 200 && !smsCodeRemainsValid(sendResult)) {
            throw SmsVerificationError(responseMessage(sendResult).ifBlank { "failed to trigger the SMS code" })
        }

        val maskedPhone = "${phone.take(3)}****${phone.takeLast(4)}"
        val code = smsCodeCallback.invoke(SmsCodeContext(phone = phone, maskedPhone = maskedPhone, purpose = "phone_primary_login")).trim()
        if (code.isEmpty()) throw SmsVerificationError("the SMS callback returned an empty code")

        val check = requestJson(
            HttpMethod.Post,
            "$casUrl/api/protected/sms/checkTokenResult",
            json = JsonObject(mapOf("phone" to JsonPrimitive(phone), "token" to JsonPrimitive(code), "delete" to JsonPrimitive(false))),
        )
        if (responseCode(check) != 200) {
            throw SmsVerificationError(responseMessage(check).ifBlank { "SMS code was rejected" })
        }

        val form = commonForm(page, "smsLogin", phone).toMutableMap()
        form["captcha_code"] = ""
        form["password"] = code
        addRiskFields(form, page, "smsLogin", phone)
        val response = loginPost(page.formAction, form, followRedirects = true)
        return loginResult(response, "短信验证码错误或已失效，请重新发起登录")
    }

    private suspend fun completeSecondFactor(
        username: String,
        page: SecondFactorPage,
        callback: SmsCodeCallback?,
        trustDevice: Boolean,
        followRedirects: Boolean,
    ): SsoLoginResult {
        loginReferer = "$casUrl/"
        val phoneData = secondFactorPhone(page)
        val opaquePhone = extractString(phoneData, "tel").ifBlank { page.phone }
        if (opaquePhone.isBlank()) throw SmsVerificationError("the second-factor page did not provide a bound phone identifier")
        val maskedPhone = extractString(phoneData, "maskTel")

        val sent = requestJson(
            HttpMethod.Post,
            "$casUrl/api/protected/sms/publicNoToken/sendSmsCode",
            json = JsonObject(mapOf("phone" to JsonPrimitive(opaquePhone), "businessNo" to JsonPrimitive("0008"))),
        )
        if (responseCode(sent) != 200 && !smsCodeRemainsValid(sent)) {
            throw SmsVerificationError(responseMessage(sent).ifBlank { "failed to send the second-factor SMS code" })
        }

        val code = callback?.invoke(SmsCodeContext(phone = "", maskedPhone = maskedPhone.ifBlank { "绑定手机" }, purpose = "password_second_factor"))?.trim()
            ?: throw SmsVerificationError("the login requires an SMS code callback")
        if (code.isEmpty()) throw SmsVerificationError("the SMS callback returned an empty code")

        val checked = requestJson(
            HttpMethod.Post,
            "$casUrl/api/protected/sms/checkToken",
            json = JsonObject(mapOf(
                "phone" to JsonPrimitive(opaquePhone),
                "token" to JsonPrimitive(code),
                "delete" to JsonPrimitive(false),
                "trustDevice" to JsonPrimitive(trustDevice),
            )),
        )
        if (responseCode(checked) != 200) {
            throw SmsVerificationError(responseMessage(checked).ifBlank { "the second-factor SMS code was rejected" })
        }

        val form = mapOf(
            "username" to username,
            "password" to code,
            "type" to "smsLogin",
            "_eventId" to "submit",
            "geolocation" to "",
            "execution" to page.execution,
            "captcha_code" to "",
            "trustDevice" to trustDevice.toString().lowercase(),
        )
        return loginResult(
            loginPost(page.formAction, form, followRedirects),
            "短信验证码错误或已失效，请重新发起登录",
        )
    }

    private suspend fun secondFactorPhone(page: SecondFactorPage): JsonObject? {
        val crypto = Crypto.encryptUrlCryptoBody(
            JsonObject(mapOf("userId" to JsonPrimitive(page.userObjectId))),
            URL_CRYPTO_PUBLIC_KEY,
        )
        val response = request(
            HttpMethod.Post,
            "$casUrl/api/protected/sms/getPhoneNumberByUserId",
            rawBody = crypto.body,
            extraHeaders = mapOf(
                "Content-Type" to "application/json",
                "hasCrypto" to "true",
                "privateKey" to crypto.encryptedKey,
            ),
        )
        if (response.bodyText.isBlank()) {
            throw ConfigurationError("the second-factor phone endpoint returned an empty encrypted response")
        }
        val decrypted = Crypto.decryptUrlCryptoResponse(response.bodyText, crypto.aesKey)
        val obj = decrypted as? JsonObject ?: return null
        return obj["data"] as? JsonObject
    }

    private fun commonForm(page: LoginPage, type: String, username: String) = mapOf(
        "type" to type,
        "_eventId" to "submit",
        "geolocation" to "",
        "execution" to page.execution,
        "username" to username,
        "croypto" to page.cryptoKey,
    )

    private suspend fun addRiskFields(form: MutableMap<String, String>, page: LoginPage, type: String, username: String) {
        if (!page.riskSystem.equals("USTC", true)) return
        val payload = if (riskTokenProvider != null) {
            lastRiskMode = "custom"
            riskTokenProvider.invoke(RiskContext(type, username, page.targetSystem, page.siteId))
        } else {
            defaultUstcRiskPayload()
        }
        form["risk_payload"] = Crypto.encryptAesBase64(compactJson(payload), page.cryptoKey)
        form["targetSystem"] = page.targetSystem.ifBlank { "sso" }
        form["siteId"] = page.siteId.ifBlank { "sourceId" }
        form["riskEngine"] = "true"
    }

    private suspend fun defaultUstcRiskPayload(): Map<String, Any?> {
        val deviceCookie = ensureDeviceCookie()
        val groupId = transport.cookieValue("riskSystemGroupId")
        val fingerprint = fingerprintProfile.build(deviceCookie, userAgent, groupId)
        val fingerprintJson = fingerprint.toJsonElement() as JsonObject
        return try {
            val result = requestJson(HttpMethod.Post, "$normalizedBaseUrl/ustc-rba-front/fp", json = fingerprintJson)
            val token = riskResponseToken(result)
            if (token.isBlank()) throw ConfigurationError("USTC risk response did not contain responsetoken")
            lastRiskMode = "ustc-token"
            mapOf("token" to token, "groupId" to groupId)
        } catch (e: BitSsoError) {
            lastRiskMode = "error-fallback"
            mapOf("error" to true)
        } catch (e: Exception) {
            lastRiskMode = "error-fallback"
            mapOf("error" to true)
        }
    }

    private fun riskResponseToken(json: JsonObject): String {
        json["responsetoken"]?.jsonPrimitive?.contentOrNullSafe()?.takeIf { it.isNotBlank() }?.let { return it }
        val data = json["data"] as? JsonObject
        return data?.get("responsetoken")?.jsonPrimitive?.contentOrNullSafe().orEmpty()
    }

    private suspend fun ensureDeviceCookie(): String {
        transport.cookieValue("device").takeIf { it.isNotBlank() }?.let { return it }
        val value = Crypto.sha256(currentTimeMillis().toString().encodeToByteArray())
        transport.setCookie("device", value, uriHost(normalizedBaseUrl) ?: normalizedBaseUrl)
        return value
    }

    private suspend fun captchaImage(value: String): ByteArray {
        if (value.startsWith("data:")) {
            val commaIndex = value.indexOf(',')
            if (commaIndex < 0) throw CaptchaError("the server supplied an invalid captcha data URL")
            val metadata = value.substring(0, commaIndex)
            val encoded = value.substring(commaIndex + 1)
            if (";base64" !in metadata) throw CaptchaError("captcha data URL is not Base64 encoded")
                return try {
                base64Decode(encoded)
            } catch (e: Throwable) {
                throw CaptchaError("the server supplied an invalid captcha data URL")
            }
        }
        val resolvedUrl = uriResolve("$casUrl/", value)
        return request(HttpMethod.Get, resolvedUrl).bodyBytes
            ?: throw ConfigurationError("captcha endpoint did not return binary data")
    }

    private suspend fun solveCaptcha(image: ByteArray, context: CaptchaContext, solver: CaptchaSolver?): String {
        if (solver == null) {
            throw CaptchaError("登录需要图形验证码，但服务器未安装或配置验证码识别器（ddddocr）")
        }
        val code = solver.invoke(image, context).trim()
        if (code.isEmpty()) throw CaptchaError("captcha_solver returned an empty value")
        return code
    }

    private suspend fun requestJson(method: HttpMethod, url: String, json: JsonObject? = null): JsonObject {
        val body = request(method, url, json = json).bodyText
        val parsed = try {
            Json.parseToJsonElement(body)
        } catch (e: Exception) {
            throw ConfigurationError("expected JSON from $url (got: ${body.take(200)})")
        }
        return parsed as? JsonObject ?: throw ConfigurationError("expected JSON object from $url")
    }

    private suspend fun request(
        method: HttpMethod,
        url: String,
        query: Map<String, String> = emptyMap(),
        form: Map<String, String>? = null,
        json: JsonObject? = null,
        rawBody: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
        allowRedirects: Boolean = true,
        cacheBust: Boolean = true,
        // Mirrors Python `response.raise_for_status()` invoked unconditionally
        // inside `_request`. The two POST /cas/login callers opt out so they
        // can route 400/401/403 rejections through `loginResult` instead.
        raiseForStatus: Boolean = true,
    ): HttpResponse {
        val headers = mutableMapOf("Referer" to loginReferer)
        if (method != HttpMethod.Get) headers["Origin"] = normalizedBaseUrl
        if ("protected" in url) headers.putAll(Crypto.protectedCsrfHeaders() + ("Sid-Language" to "zh_CN"))
        headers.putAll(extraHeaders)
        val finalUrl = if (method == HttpMethod.Get && cacheBust) {
            val sep = if ('?' in url) "&" else "?"
            "$url$sep${currentTimeMillis()}"
        } else {
            url
        }
        val response = transport.request(SsoRequest(method, finalUrl, headers, query, form, json, rawBody, allowRedirects))
        if (raiseForStatus && response.status in 400..599) {
            throw SsoHttpException(response.status, response.url, response.bodyText)
        }
        return response
    }

    private suspend fun loginPost(
        url: String,
        form: Map<String, String>,
        followRedirects: Boolean,
    ): HttpResponse {
        val response = request(
            HttpMethod.Post,
            url,
            form = form,
            allowRedirects = followRedirects,
            raiseForStatus = false,
        )
        val isLoginRejection = response.status in setOf(400, 401, 403) &&
            uriPath(response.url).trimEnd('/').endsWith("/cas/login")
        if (response.status in 400..599 && !isLoginRejection) {
            throw SsoHttpException(response.status, response.url, response.bodyText)
        }
        return response
    }

    private fun loginResult(response: HttpResponse, rejectionMessage: String = "统一身份认证拒绝了登录请求"): SsoLoginResult {
        val parsed = SsoParser.parseLoginPage(response.bodyText, response.url)
        val secondFactorPage = SsoParser.parseSecondFactorPage(response.bodyText, response.url)
        val ticket = findTicket(response)
        val isLoginUrl = response.url.substringBefore('?').trimEnd('/').endsWith("/cas/login")
        val loginMarkup = listOf("login-page-flowkey", "normalLoginForm", "smsLoginForm", "secondSmsLoginForm", "cas-gateway").any { it in response.bodyText }
        val loginRejected = isLoginUrl && response.status in setOf(400, 401, 403)
        if (parsed.page != null || secondFactorPage != null || (isLoginUrl && loginMarkup) || loginRejected) {
            val returnedExecution = parsed.page?.execution ?: secondFactorPage?.execution ?: ""
            val flowReplaced = if (returnedExecution.isNotEmpty() && lastExecution.isNotEmpty()) {
                returnedExecution != lastExecution
            } else null
            val (message, code) = if (loginRejected && parsed.errorMessage.isBlank() && parsed.errorCode.isBlank()) {
                rejectionMessage to parsed.errorCode
            } else {
                formatLoginError(parsed.errorMessage, parsed.errorCode, ticketIssued = ticket != null, riskMode = lastRiskMode)
            }
            throw LoginError(
                message = message,
                code = code,
                finalUrl = response.url,
                statusCode = response.status,
                redirectCount = 0,
                ticketIssued = ticket != null,
                riskMode = lastRiskMode,
                flowReplaced = flowReplaced,
                captchaRequired = lastCaptchaRequired,
            )
        }
        return SsoLoginResult(response, response.url, ticket, transport.cookieMap())
    }

    private fun findTicket(response: HttpResponse, history: List<HttpResponse> = emptyList()): String? {
        val candidates = history + response
        for (item in candidates) {
            val urls = mutableListOf(item.url)
            item.location()?.let { loc -> urls.add(uriResolve(item.url, loc)) }
            for (url in urls) {
                val query = uriQuery(url) ?: continue
                query.split("&").firstOrNull { it.startsWith("ticket=") }?.substringAfter("=")?.let { return it }
            }
        }
        return null
    }

    private fun responseMessage(json: JsonObject): String {
        for (key in listOf("message", "msg", "errorMessage")) {
            json[key]?.jsonPrimitive?.contentOrNullSafe()?.takeIf { it.isNotBlank() }?.let { return it }
        }
        val data = json["data"] as? JsonObject
        if (data != null) {
            for (key in listOf("message", "msg", "errorMessage")) {
                data[key]?.jsonPrimitive?.contentOrNullSafe()?.takeIf { it.isNotBlank() }?.let { return it }
            }
        }
        return ""
    }

    private fun responseData(value: JsonElement?): JsonObject? = (value as? JsonObject)?.get("data") as? JsonObject

    private fun responseCode(json: JsonObject): Int? = json["code"]?.jsonPrimitive?.intOrNull

    private fun extractString(value: JsonObject?, key: String): String =
        value?.get(key)?.jsonPrimitive?.contentOrNullSafe().orEmpty()

    private fun smsCodeRemainsValid(json: JsonObject): Boolean {
        val msg = responseMessage(json)
        return "验证码" in msg && "有效期内" in msg && "重复发送" in msg
    }

    private fun compactJson(value: Map<String, Any?>): String =
        Json.encodeToString(JsonElement.serializer(), value.toJsonElement())

    private fun compactJson(value: JsonObject): String =
        Json.encodeToString(JsonObject.serializer(), value)

    private fun encode(value: String): String = PythonUrlEncoding.quote(value, safe = "")

    companion object {
        val PHONE_PATTERN = Regex("^1[0-9]{10}$")
        const val DEFAULT_BASE_URL = "https://sso.bit.edu.cn"
        const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36"
        const val URL_CRYPTO_PUBLIC_KEY = """-----BEGIN PUBLIC KEY-----
MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAjVr1zKwohU3xA0afprWLSQvIymaSH/V27MedFc+CecXSnORIFMAp4uEIb4taDq/2X4eMeTI66Mu/rB5GKSFDbExF2Gu4NaO/CNDpf1gHMScUrIFCh4CDqzBnx17kclvezLkIK0T8FVa4cRsINvzjbnA6jUSMaf6Fm1n9wTAtW6QYBjssGOEtCj+c38PTBdFMmJbXp3brt1tEBesz6lb3Fjp76FGvDZ08xtYG8fxYPuiMwKU04eS+mcX/BunwgpU3zwekHYB+PWRIvq0lBry9Wms25sJE5T/RAv5fEuMLbBkfcZK3+7ivSZthTmPpr2Ap/ji70ZZ6u2jvR5VJq+LJHQIDAQAB
-----END PUBLIC KEY-----"""

        /** Browser default headers matching Python `BitSsoClient.__init__` session defaults. */
        val BROWSER_DEFAULT_HEADERS: Map<String, String> = mapOf(
            "User-Agent" to DEFAULT_USER_AGENT,
            "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.7",
            "sec-ch-ua" to "\"Not;A=Brand\";v=\"8\", \"Chromium\";v=\"150\", \"Google Chrome\";v=\"150\"",
            "sec-ch-ua-mobile" to "?0",
            "sec-ch-ua-platform" to "\"macOS\"",
        )
    }
}

private fun jsonTruthy(element: JsonElement?): Boolean = when (element) {
    null, JsonNull -> false
    is JsonPrimitive -> when {
        element.isString -> element.content.isNotEmpty()
        element.content == "false" -> false
        element.content == "0" -> false
        else -> true
    }
    is JsonObject -> element.isNotEmpty()
    is JsonArray -> element.isNotEmpty()
}

private fun JsonPrimitive.contentOrNullSafe(): String? = content

private fun Map<String, Any?>.toJsonElement(): JsonElement = JsonObject(entries.associate { (k, v) ->
    k to v.toJsonElement()
})

private fun Any?.toJsonElement(): JsonElement = when (this) {
    null -> JsonNull
    is JsonElement -> this
    is Number -> JsonPrimitive(this)
    is Boolean -> JsonPrimitive(this)
    is String -> JsonPrimitive(this)
    is Map<*, *> -> JsonObject(entries.associate { (key, value) -> key.toString() to value.toJsonElement() })
    is Iterable<*> -> JsonArray(map { it.toJsonElement() })
    else -> JsonPrimitive(toString())
}

private class HttpClientTransport(private val client: HttpClient) : SsoTransport {
    override suspend fun request(request: SsoRequest): HttpResponse =
        client.request(request.method, request.url, request.headers, request.query, request.form, request.json, request.rawBody, request.allowRedirects)
    override suspend fun requestBytes(request: SsoRequest): ByteArray =
        client.requestBytes(request.method, request.url, request.headers, request.query, request.form, request.json, request.rawBody, request.allowRedirects)
    override fun cookieValue(name: String): String = client.cookieValue(name).orEmpty()
    override fun cookieMap(): Map<String, String> = client.cookieMap()
    override suspend fun setCookie(name: String, value: String, domain: String) = client.addCookie(name, value, domain)
}

class SessionSsoTransport(private val sessionProvider: () -> HttpClient) : SsoTransport {
    override suspend fun request(request: SsoRequest): HttpResponse =
        sessionProvider().request(request.method, request.url, request.headers, request.query, request.form, request.json, request.rawBody, request.allowRedirects)
    override suspend fun requestBytes(request: SsoRequest): ByteArray =
        sessionProvider().requestBytes(request.method, request.url, request.headers, request.query, request.form, request.json, request.rawBody, request.allowRedirects)
    override fun cookieValue(name: String): String = sessionProvider().cookieValue(name).orEmpty()
    override fun cookieMap(): Map<String, String> = sessionProvider().cookieMap()
    override suspend fun setCookie(name: String, value: String, domain: String) =
        sessionProvider().addCookie(name, value, domain)
}
