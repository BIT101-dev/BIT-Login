import requests

from server.core.config import HTTP_TIMEOUT


def configure_default_timeout():
    request = requests.sessions.Session.request
    if getattr(request, "_bit_login_timeout_patched", False):
        return

    original_request = request

    def request_with_timeout(self, method, url, **kwargs):
        kwargs.setdefault("timeout", HTTP_TIMEOUT)
        return original_request(self, method, url, **kwargs)

    request_with_timeout._bit_login_timeout_patched = True
    requests.sessions.Session.request = request_with_timeout
