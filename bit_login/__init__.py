"""
BIT Login - 北京理工大学统一身份认证登录库

这个库提供了简单易用的接口来处理北京理工大学统一身份认证登录。
"""

from .login import login_error, login
from .service import webvpn_login, jwb_login, jwb_cjd_login, ibit_login, yanhekt_login, library_login, jxzxehall_login, dekt_login, cxcy_login
from .services import jwb,jxzxehall

__version__ = "3.4.3"
__author__ = "Teclab"
__email__ = "admin@teclab.org.cn"
__description__ = "北京理工大学统一身份认证登录库"

__all__ = ["login_error", "login", "webvpn_login", "jwb_login", "jwb_cjd_login", "ibit_login", "yanhekt_login","library_login", "jxzxehall_login", "dekt_login", "jwb", "jxzxehall", "cxcy_login"]
