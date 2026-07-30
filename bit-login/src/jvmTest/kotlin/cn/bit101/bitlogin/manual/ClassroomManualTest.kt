package cn.bit101.bitlogin.manual

import cn.bit101.bitlogin.api.jxzxehall.Classroom
import cn.bit101.bitlogin.login.LoginError
import cn.bit101.bitlogin.login.SsoLogin
import cn.bit101.bitlogin.service.JxzxehallLogin
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.time.LocalDate
import kotlin.system.exitProcess

/**
 * 空闲教室接口可用性交互测试.
 *
 * 运行: ./gradlew :bit-login:classroomManualTest
 *
 * 凭据优先级: 环境变量 BIT_USERNAME / BIT_PASSWORD > 终端交互输入.
 * 支持短信二次验证与图形验证码（验证码图片保存到临时文件后人工识别）.
 */
fun main(): Unit = runBlocking {
    println("=== 空闲教室 (jxzxehall Classroom) 可用性测试 ===")
    println("网络环境会自动探测：校内直连 / 校外走 WebVPN")
    println()

    var failures = 0
    fun check(cond: Boolean, msg: String) {
        if (cond) println("  [PASS] $msg") else {
            failures++
            println("  [FAIL] $msg")
        }
    }

    val exitCode = try {
        val username = System.getenv("BIT_USERNAME")?.takeIf { it.isNotBlank() }
            ?: prompt("学号/工号: ")
        val password = System.getenv("BIT_PASSWORD")?.takeIf { it.isNotBlank() }
            ?: promptSecret("统一身份认证密码: ")
        if (username.isBlank() || password.isBlank()) {
            println("用户名或密码为空，终止测试")
            exitProcess(2)
        }

        val login = JxzxehallLogin(
            SsoLogin(
                smsCodeCallback = { ctx ->
                    println("需要短信二次验证，验证码已发送至 ${ctx.maskedPhone.ifBlank { ctx.phone }}")
                    prompt("请输入短信验证码: ")
                },
                captchaSolver = { bytes, _ ->
                    val file = Files.write(Files.createTempFile("bit-login-captcha-", ".png"), bytes)
                    println("登录要求图形验证码，图片已保存到: $file")
                    println("请打开该文件查看后输入")
                    prompt("图形验证码: ")
                },
            )
        )

        print("登录教学中心 (jxzxehall)... ")
        var t0 = System.currentTimeMillis()
        login.login(username, password)
        val loginMs = System.currentTimeMillis() - t0
        val session = login.getSession()
        println("成功 (${loginMs} ms)")
        check(true, "SSO 登录 + jxzxehall 会话建立")

        val dateDefault = LocalDate.now().toString()
        val date = prompt("查询日期 [YYYY-MM-DD，回车默认今天 $dateDefault]: ").ifBlank { dateDefault }
        val building = prompt("教学楼名称过滤（如 理学A，回车查全部）: ").ifBlank { null }

        println()
        print("查询 $date ${building ?: "全部教学楼"} 的教室占用... ")
        t0 = System.currentTimeMillis()
        val rooms = Classroom(session).getOccupancy(dateStr = date, classroomName = building)
        val queryMs = System.currentTimeMillis() - t0
        println("完成 (${queryMs} ms，共 ${rooms.size} 间)")
        println()

        println("结构校验:")
        check(rooms.isNotEmpty(), "返回结果非空 (${rooms.size} 间教室)")

        val requiredKeys = listOf("name", "building_code", "type", "seats", "status")
        check(
            rooms.all { r -> requiredKeys.all { r.containsKey(it) } },
            "每条记录包含 ${requiredKeys.joinToString("/")} 字段"
        )
        check(
            rooms.all { (it["name"] as? String)?.isNotBlank() == true },
            "所有教室名称非空"
        )

        @Suppress("UNCHECKED_CAST")
        fun statusOf(room: Map<String, Any?>): Map<Int, Map<String, String>>? =
            room["status"] as? Map<Int, Map<String, String>>

        check(
            rooms.all { statusOf(it)?.keys == (1..13).toSet() },
            "所有教室包含第 1-13 节课状态"
        )
        check(
            rooms.all { room ->
                statusOf(room)?.values?.all { slot ->
                    !slot["state"].isNullOrBlank() &&
                            !slot["start"].isNullOrBlank() &&
                            !slot["end"].isNullOrBlank()
                } == true
            },
            "每节课的 state/start/end 均非空"
        )

        println()
        println("参数校验:")
        val badDateRejected = try {
            Classroom(session).getOccupancy("2026/01/01")
            false
        } catch (_: IllegalArgumentException) {
            true
        }
        check(badDateRejected, "非法日期格式被拒绝 (IllegalArgumentException)")

        println()
        println("空闲统计 (state == 空闲):")
        for (jc in 1..13) {
            val free = rooms.count { statusOf(it)?.get(jc)?.get("state") == "空闲" }
            val first = statusOf(rooms.first())?.get(jc)
            println("  第 ${jc.toString().padStart(2)} 节 (${first?.get("start")}-${first?.get("end")}): $free/${rooms.size} 间空闲")
        }

        println()
        println("示例教室:")
        rooms.take(5).forEach { room ->
            val occupiedSlots = statusOf(room)
                ?.filterValues { it["state"] != "空闲" }
                ?.entries?.joinToString(", ") { (jc, slot) -> "第${jc}节:${slot["state"]}" }
                ?.ifBlank { "全天空闲" }
            println("  ${room["name"]} | ${room["type"]} | 座位 ${room["seats"]} | $occupiedSlots")
        }

        session.close()

        println()
        if (failures == 0) println("全部检查通过，空闲教室接口可用。")
        else println("$failures 项检查未通过，接口返回异常。")
        if (failures == 0) 0 else 1
    } catch (e: LoginError) {
        println()
        println("登录失败: ${e.message}")
        1
    } catch (e: Throwable) {
        println()
        println("测试失败: ${e.message}")
        e.printStackTrace()
        1
    }
    exitProcess(exitCode)
}

private fun prompt(label: String): String {
    print(label)
    return readlnOrNull()?.trim() ?: ""
}

private fun promptSecret(label: String): String {
    val console = System.console()
    if (console != null) {
        return console.readPassword(label)?.concatToString()?.trim() ?: ""
    }
    print("$label(明文回显) ")
    return readlnOrNull()?.trim() ?: ""
}
