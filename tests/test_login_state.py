import unittest

from bit_login.login import login


class LoginStateTests(unittest.TestCase):
    def test_session_and_second_auth_state_round_trip(self):
        source = login()
        source.session.headers["X-Test"] = "header"
        source.session.cookies.set(
            "ticket",
            "value",
            domain="sso.bit.edu.cn",
            path="/cas",
            secure=True,
            expires=2000000000,
            rest={"HttpOnly": None},
        )
        source.second_auth.start({
            "execution": "flow-key",
            "phone": "13800000000",
            "masked_phone": "138****0000",
            "page_url": "https://sso.bit.edu.cn/cas/login",
        })

        restored = login().restore_state(source.export_state())
        cookie = next(iter(restored.session.cookies))

        self.assertEqual(restored.session.headers["X-Test"], "header")
        self.assertEqual(cookie.domain, "sso.bit.edu.cn")
        self.assertEqual(cookie.path, "/cas")
        self.assertTrue(cookie.secure)
        self.assertEqual(cookie.expires, 2000000000)
        self.assertEqual(restored.second_auth.state["execution"], "flow-key")


if __name__ == "__main__":
    unittest.main()
