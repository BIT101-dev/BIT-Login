package cn.bit101.bitlogin.manual

import cn.bit101.bitlogin.api.jwb.Score
import cn.bit101.bitlogin.login.LoginError
import cn.bit101.bitlogin.login.SsoLogin
import cn.bit101.bitlogin.service.JwbLogin
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.system.exitProcess

/**
 * 教务系统成绩查询接口可用性交互测试.
 *
 * 运行: ./gradlew :bit-login:scoreManualTest
 *
 * 凭据优先级: 环境变量 BIT_USERNAME / BIT_PASSWORD > 终端交互输入.
 * 支持短信二次验证与图形验证码（验证码图片保存到临时文件后人工识别）.
 */
fun main(): Unit = runBlocking {
    println("=== 教务系统成绩查询 (jwb Score) 可用性测试 ===")
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

        val login = JwbLogin(
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

        print("登录教务系统 (jwb)... ")
        var t0 = System.currentTimeMillis()
        login.login(username, password)
        val loginMs = System.currentTimeMillis() - t0
        val session = login.getSession()
        println("成功 (${loginMs} ms)")
        check(true, "SSO 登录 + jwb 会话建立")

        val score = Score(session)
        val kksj = prompt("开课学期过滤 [如 2025-2026-1，回车查询当前学期]: ").ifBlank { null }

        println()
        print("查询成绩... ")
        t0 = System.currentTimeMillis()
        val scores = score.getScore(kksj = kksj, detailed = false)
        val queryMs = System.currentTimeMillis() - t0
        println("完成 (${queryMs} ms，共 ${scores.size} 条)")
        println()

        println("结构校验:")
        check(scores.isNotEmpty(), "返回结果非空 (${scores.size} 条)")

        val requiredKeys = listOf("student", "course", "score", "credit", "hours", "kksj", "type")
        check(
            scores.all { r -> requiredKeys.all { r.containsKey(it) } },
            "每条记录包含 ${requiredKeys.joinToString("/")} 字段"
        )
        check(
            scores.all { (it["course"] as? String)?.isNotBlank() == true },
            "所有课程名称非空"
        )
        check(
            scores.all { (it["student"] as? String)?.isNotBlank() == true },
            "所有学生姓名非空"
        )
        check(
            scores.all { (it["score"] as? String)?.isNotBlank() == true },
            "所有成绩非空"
        )

        println()
        println("成绩列表 (前 10 条):")
        scores.take(10).forEachIndexed { i, s ->
            println(
                "  ${i + 1}. ${s["kksj"]} | ${s["course"]} | " +
                    "成绩 ${s["score"]} | 学分 ${s["credit"]} | ${s["type"]}"
            )
        }

        println()
        print("查询全部成绩 (all_score)... ")
        t0 = System.currentTimeMillis()
        val allScores = score.getAllScore(detailed = false)
        val allMs = System.currentTimeMillis() - t0
        println("完成 (${allMs} ms，共 ${allScores.size} 条)")
        check(allScores.isNotEmpty(), "全部成绩非空 (${allScores.size} 条)")
        check(
            allScores.all { r -> requiredKeys.all { r.containsKey(it) } },
            "每条全部成绩记录包含 ${requiredKeys.joinToString("/")} 字段"
        )

        println()
        print("查询 bit101 格式成绩... ")
        t0 = System.currentTimeMillis()
        val bit101 = score.getBit101Score(kksj = kksj, detailed = false)
        val bit101Ms = System.currentTimeMillis() - t0
        println("完成 (${bit101Ms} ms，${bit101.size} 行)")
        check(bit101.isNotEmpty(), "bit101 格式结果非空")
        check(
            bit101.all { it.size == 16 },
            "bit101 每行包含 16 列 (表头 + 数据)"
        )

        println()
        println("bit101 表头:")
        bit101.firstOrNull()?.forEachIndexed { i, h -> println("  [$i] $h") }
        println("bit101 数据行 (前 5 条):")
        bit101.drop(1).take(5).forEachIndexed { i, row ->
            println("  ${i + 1}. ${row[1]} | ${row[3]} | 成绩 ${row[4]} | 学分 ${row[6]}")
        }

        session.close()

        println()
        if (failures == 0) println("全部检查通过，教务成绩查询接口可用。")
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
