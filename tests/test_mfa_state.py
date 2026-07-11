import base64
import os
import unittest
from unittest import mock

from server.core.mfa_state import (
    ExpiredChallenge,
    InvalidChallenge,
    issue_challenge,
    restore_challenge,
)


class ChallengeTokenTests(unittest.TestCase):
    def setUp(self):
        self.key = base64.urlsafe_b64encode(b"k" * 32).decode("ascii")
        self.environment = mock.patch.dict(
            os.environ,
            {"MFA_STATE_KEY": self.key, "MFA_STATE_TTL": "300"},
        )
        self.environment.start()

    def tearDown(self):
        self.environment.stop()

    def test_round_trip_and_confidentiality(self):
        state = {"cookie": "secret-cookie", "execution": "secret-flow"}
        token = issue_challenge("student", "jwb", state, now=100)

        self.assertNotIn("student", token)
        self.assertNotIn("secret-cookie", token)
        payload = restore_challenge(token, "student", now=101)
        self.assertEqual(payload["service"], "jwb")
        self.assertEqual(payload["state"], state)

    def test_tampered_token_is_rejected(self):
        token = issue_challenge("student", "jwb", {}, now=100)
        replacement = "A" if token[-1] != "A" else "B"
        with self.assertRaises(InvalidChallenge):
            restore_challenge(token[:-1] + replacement, "student", now=101)

    def test_expired_token_is_rejected(self):
        token = issue_challenge("student", "jwb", {}, now=100)
        with self.assertRaises(ExpiredChallenge):
            restore_challenge(token, "student", now=400)

    def test_username_is_bound_to_token(self):
        token = issue_challenge("student", "jwb", {}, now=100)
        with self.assertRaises(InvalidChallenge):
            restore_challenge(token, "other", now=101)


if __name__ == "__main__":
    unittest.main()
