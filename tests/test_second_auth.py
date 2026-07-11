import unittest
from unittest import mock

from bit_login.login import login


class SecondAuthTests(unittest.TestCase):
    def setUp(self):
        self.client = login()
        self.client.second_auth.start({
            "execution": "flow-key",
            "phone": "13800000000",
            "masked_phone": "138****0000",
            "page_url": "https://sso.bit.edu.cn/cas/login",
        })

    def test_dingtalk_uses_frontend_api_and_completes_cas_form(self):
        login_id_response = mock.Mock()
        login_id_response.json.return_value = {"code": 200, "data": "login-id"}
        qr_response = mock.Mock(
            status_code=200,
            content=b"qr",
            headers={"Content-Type": "image/png"},
        )
        poll_response = mock.Mock()
        poll_response.json.return_value = {"code": 200, "data": "scan-token"}

        with mock.patch.object(
            self.client.session, "request", side_effect=[login_id_response, poll_response]
        ) as request, mock.patch.object(
            self.client.session, "get", return_value=qr_response
        ) as get, mock.patch.object(
            self.client.second_auth, "_complete", return_value={"callback": "service"}
        ) as complete:
            qr = self.client.begin_dingtalk_qr()
            result = self.client.poll_dingtalk_qr()

        self.assertEqual(qr["qr_url"], "https://sso.bit.edu.cn/gate/api/public/qrlogin/qrgen/login-id/dingDingQr")
        self.assertEqual(result, {"callback": "service"})
        self.assertEqual(request.call_args_list[0].args[1], "https://sso.bit.edu.cn/gate/api/protected/qrlogin/loginid")
        self.assertEqual(request.call_args_list[1].args[1], "https://sso.bit.edu.cn/gate/api/protected/qrlogin/scan/login-id")
        get.assert_called_once_with(qr["qr_url"], headers={"Referer": "https://sso.bit.edu.cn/cas/login"})
        complete.assert_called_once_with({
            "username": "scan-token",
            "type": "dingDingQr",
            "_eventId": "submit",
            "execution": "flow-key",
            "geolocation": "",
        }, self.client.second_auth.state or mock.ANY)


if __name__ == "__main__":
    unittest.main()
