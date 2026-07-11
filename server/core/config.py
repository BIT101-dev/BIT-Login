import os


ALLOWED_ORIGINS = [
    "https://bit101.cn",
    "http://bit101.cn",
    "http://127.0.0.1:3000",
    "http://localhost:3000",
    "https://deploy-preview-57--bit101-demo.netlify.app",
    "http://deploy-preview-57--bit101-demo.netlify.app",
]

BASE_URL = os.getenv("BASE_URL", "https://login.bit101.flwfdd.xyz")
HTTP_CONNECT_TIMEOUT = float(os.getenv("HTTP_CONNECT_TIMEOUT", "5"))
HTTP_READ_TIMEOUT = float(os.getenv("HTTP_READ_TIMEOUT", "25"))
HTTP_TIMEOUT = (HTTP_CONNECT_TIMEOUT, HTTP_READ_TIMEOUT)
