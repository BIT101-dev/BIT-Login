package cn.bit101.bitlogin.server.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import cn.bit101.bitlogin.server.auth.AuthServices
import cn.bit101.bitlogin.server.auth.AuthWorker
import cn.bit101.bitlogin.server.auth.ChallengeError
import cn.bit101.bitlogin.server.auth.ChallengeStore
import cn.bit101.bitlogin.server.auth.RegistrationAudienceError
import cn.bit101.bitlogin.server.auth.RegistrationToken
import cn.bit101.bitlogin.server.auth.RegistrationTokenError
import cn.bit101.bitlogin.server.model.AuthStartRequest
import cn.bit101.bitlogin.server.model.CaptchaCodeRequest
import cn.bit101.bitlogin.server.model.RegistrationTokenRequest
import cn.bit101.bitlogin.server.model.SmsCodeRequest
import cn.bit101.bitlogin.server.plugins.HttpException
import cn.bit101.bitlogin.server.util.toJsonElement

private suspend fun challengeOrHttp(store: ChallengeStore, challengeId: String, token: String?): Map<String, Any?> {
    return try {
        store.authenticate(challengeId, token ?: "")
    } catch (e: ChallengeError) {
        val status = if (e.message?.contains("access token") == true) 403 else 404
        throw HttpException(status, e.message ?: "unknown error")
    }
}

fun Route.authRoutes(worker: AuthWorker, store: ChallengeStore) {
    route("/api/auth") {
        post("/start") {
            val req = call.receive<AuthStartRequest>()
            val services = req.services.toSet().toList()
            val invalid = services.filter { it !in AuthServicesList }.sorted()
            if (services.isEmpty() || invalid.isNotEmpty()) {
                throw HttpException(
                    422,
                    "invalid authentication services",
                    jsonBody = buildJsonObject {
                        put("message", "invalid authentication services")
                        put("invalid", JsonArray(invalid.map { JsonPrimitive(it) }))
                        put("supported", JsonArray(AuthServicesList.map { JsonPrimitive(it) }))
                    },
                )
            }
            if (req.waitSeconds !in 0.0..5.0) {
                throw HttpException(422, "wait_seconds must be between 0 and 5")
            }
            if (req.authMethod !in setOf("password", "sms")) {
                throw HttpException(422, "auth_method must be password or sms")
            }
            if (req.username.isBlank()) throw HttpException(422, "username is required")
            if (req.authMethod == "password" && req.password.isEmpty()) {
                throw HttpException(422, "password is required for password authentication")
            }
            if (req.authMethod == "sms" && !Regex("^1[0-9]{10}$").matches(req.phone.trim())) {
                throw HttpException(422, "phone must be an 11-digit mainland China mobile number for SMS authentication")
            }
            val handle = worker.startAuthentication(
                req.username.trim(),
                req.password,
                req.phone.trim(),
                req.authMethod,
                services,
                req.username.trim(),
            )
            val waitMs = (req.waitSeconds * 1000).toLong()
            store.waitUntilActionable(handle.challengeId, handle.accessToken, waitMs)
            call.respond(
                HttpStatusCode.Accepted,
                store.snapshot(handle.challengeId, handle.accessToken, includeAccessToken = true).toJsonElement()
            )
        }

        get("/{challengeId}") {
            val challengeId = call.parameters["challengeId"]!!
            val token = call.request.headers["X-Challenge-Token"]
            challengeOrHttp(store, challengeId, token)
            call.respond(store.snapshot(challengeId, token ?: "").toJsonElement())
        }

        post("/{challengeId}/sms") {
            val challengeId = call.parameters["challengeId"]!!
            val token = call.request.headers["X-Challenge-Token"]
            val req = call.receive<SmsCodeRequest>()
            challengeOrHttp(store, challengeId, token)
            try {
                store.submitSms(challengeId, token ?: "", req.code)
            } catch (e: ChallengeError) {
                throw HttpException(409, e.message ?: "SMS error")
            }
            store.waitUntilActionable(challengeId, token ?: "", 1000)
            call.respond(store.snapshot(challengeId, token ?: "").toJsonElement())
        }

        post("/{challengeId}/captcha") {
            val challengeId = call.parameters["challengeId"]!!
            val token = call.request.headers["X-Challenge-Token"]
            val req = call.receive<CaptchaCodeRequest>()
            challengeOrHttp(store, challengeId, token)
            try {
                store.submitCaptcha(challengeId, token ?: "", req.code)
            } catch (e: ChallengeError) {
                throw HttpException(409, e.message ?: "captcha error")
            }
            store.waitUntilActionable(challengeId, token ?: "", 1000)
            call.respond(store.snapshot(challengeId, token ?: "").toJsonElement())
        }

        get("/{challengeId}/services/{service}") {
            val challengeId = call.parameters["challengeId"]!!
            val service = call.parameters["service"]!!
            val token = call.request.headers["X-Challenge-Token"]
            challengeOrHttp(store, challengeId, token)
            if (service !in AuthServicesList) throw HttpException(404, "unknown authentication service")
            try {
                val result = store.getResult(challengeId, token ?: "", service)
                call.respond(buildJsonObject {
                    put("service", service)
                    put("data", result)
                })
            } catch (e: ChallengeError) {
                throw HttpException(409, e.message ?: "service not ready")
            }
        }

        post("/{challengeId}/registration-token") {
            val challengeId = call.parameters["challengeId"]!!
            val token = call.request.headers["X-Challenge-Token"]
            val req = call.receive<RegistrationTokenRequest>()
            val state = challengeOrHttp(store, challengeId, token)
            if (state["status"] != "authenticated")
                throw HttpException(409, "challenge is ${state["status"]}")
            try {
                val (jwt, ttl) = RegistrationToken.issue(state["subject"] as? String ?: "", challengeId, req.audience)
                call.respond(buildJsonObject {
                    put("registration_token", jwt)
                    put("token_type", "Bearer")
                    put("expires_in", ttl)
                    put("audience", req.audience.trim())
                })
            } catch (e: RegistrationAudienceError) {
                throw HttpException(422, e.message ?: "audience not allowed")
            } catch (e: RegistrationTokenError) {
                throw HttpException(503, "registration token service is not configured")
            }
        }

        delete("/{challengeId}") {
            val challengeId = call.parameters["challengeId"]!!
            val token = call.request.headers["X-Challenge-Token"]
            challengeOrHttp(store, challengeId, token)
            store.delete(challengeId, token ?: "")
            call.respond(buildJsonObject { put("status", "deleted") })
        }
    }
}

private val AuthServicesList = AuthServices.names.sorted()
