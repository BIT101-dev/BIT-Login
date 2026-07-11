import unittest
from unittest import mock

import bit_login.service as service_module
from bit_login.service import jwb_login, webvpn_login


class ServiceStateTests(unittest.TestCase):
    def setUp(self):
        self.network_state = (
            service_module.network_initialized,
            service_module.webvpn_mode,
            service_module.CONFIG["urls"].get("active"),
        )
        service_module.network_initialized = True
        service_module.webvpn_mode = True
        service_module.CONFIG["urls"]["active"] = service_module.CONFIG["urls"]["webvpn"].copy()
        service_module.CONFIG["urls"]["active"].update(service_module.CONFIG["urls"]["base"])

    def tearDown(self):
        initialized, webvpn, active = self.network_state
        service_module.network_initialized = initialized
        service_module.webvpn_mode = webvpn
        if active is None:
            service_module.CONFIG["urls"].pop("active", None)
        else:
            service_module.CONFIG["urls"]["active"] = active

    def test_nested_webvpn_login_is_restored_as_pending_and_active_login(self):
        source = jwb_login(initialize_network=False)
        pending = webvpn_login(initialize_network=False)
        pending._sso_login.second_auth.start({
            "execution": "nested-flow",
            "phone": "13800000000",
            "masked_phone": "138****0000",
            "page_url": "https://sso.bit.edu.cn/cas/login",
        })
        source._pending_login = pending
        source._webvpn_login = pending
        source._resume_result = {"callback": "http://jwms.bit.edu.cn/"}

        with mock.patch.object(jwb_login, "initialize_network") as initialize:
            restored = jwb_login(initialize_network=False)
            restored.restore_second_auth_state(
                source.export_second_auth_state(), "student", "password"
            )

        initialize.assert_not_called()
        self.assertIs(restored._pending_login, restored._webvpn_login)
        self.assertEqual(
            restored._pending_login._sso_login.second_auth.state["execution"],
            "nested-flow",
        )
        self.assertEqual(restored._credentials, ("student", "password"))


if __name__ == "__main__":
    unittest.main()
