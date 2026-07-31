package cn.bit101.bitlogin.manual

import cn.bit101.bitlogin.Config
import cn.bit101.bitlogin.NetworkEnv
import cn.bit101.bitlogin.api.lexue.LexueCalendar
import cn.bit101.bitlogin.login.LoginError
import cn.bit101.bitlogin.login.SsoLogin
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.system.exitProcess

/**
 * 乐学日历导出接口可用性交互测试.
 *
 * 运行: ./gradlew :bit-login:lexueCalendarManualTest
 *
 * 凭据优先级: 环境变量 BIT_USERNAME / BIT_PASSWORD > 终端交互输入.
 * 支持短信二次验证与图形验证码（验证码图片保存到临时文件后人工识别）.
 *
 * 流程: SSO 登录（service 为乐学真实回调 {base}/login/index.php）→ LexueCalendar 建立
 * Moodle 会话并导出日历，验证真实登录重定向链与 ICS 解析.
 */
fun main(): Unit = runBlocking {
    println("=== 乐学日历 (LexueCalendar) 可用性测试 ===")
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

        NetworkEnv.ensureInitialized()
        val lexue = Config.Urls.active["lexue"]
            ?: throw LoginError("lexue 地址未配置")
        val sso = SsoLogin(
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

        print("统一身份认证登录 (service=$lexue/login/index.php)... ")
        var t0 = System.currentTimeMillis()
        sso.login(username, password, callbackUrl = "$lexue/login/index.php")
        val loginMs = System.currentTimeMillis() - t0
        println("成功 (${loginMs} ms)")
        check(true, "SSO 登录并取得统一身份认证会话 Cookie")

        val session = sso.session
        val calendar = LexueCalendar(session)

        println()
        print("建立乐学会话并获取日历订阅链接... ")
        t0 = System.currentTimeMillis()
        val url = calendar.getCalendarUrl()
        val urlMs = System.currentTimeMillis() - t0
        println("成功 (${urlMs} ms)")
        check(url.startsWith("http"), "日历订阅链接为绝对地址 (${url.take(60)}…)")

        println()
        print("拉取并解析 ICS 日历... ")
        t0 = System.currentTimeMillis()
        val events = calendar.getCalendar(url)
        val icsMs = System.currentTimeMillis() - t0
        println("完成 (${icsMs} ms，共 ${events.size} 条)")
        println()

        check(events.isNotEmpty(), "日历事件非空")
        check(
            events.all { it.uid.isNotBlank() && it.event.isNotBlank() },
            "每条事件的 uid 与标题非空"
        )

        println()
        println("结构校验:")
        check(
            events.groupBy { it.course }.all { (course, list) -> course.isNotBlank() && list.isNotEmpty() },
            "事件按课程分组，课程名非空"
        )

        println()
        println("示例事件 (前 5 条):")
        events.take(5).forEachIndexed { i, e ->
            println("  ${i + 1}. ${e.time} | ${e.event} | ${e.course}")
        }

        session.close()

        println()
        if (failures == 0) println("全部检查通过，乐学日历接口可用。")
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
