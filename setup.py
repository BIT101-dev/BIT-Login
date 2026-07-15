import setuptools #导入setuptools打包工具
 
with open("README.md", "r", encoding="utf-8") as fh:
    long_description = fh.read()

setuptools.setup(
    name="bit_login", 
    version="4.0.0",
    author="teclab",   
    author_email="admin@teclab.org.cn",
    description="北京理工大学统一身份验证登录模块",
    keywords="BIT, BITCAS, BITLogin, BITWebVPN, BITSSO, BITSSOLogin, BITSSOClient",
    long_description=long_description,   
    long_description_content_type="text/markdown",
    packages=setuptools.find_packages(),
    install_requires=[
        "beautifulsoup4",
        "cryptography",
        "pycryptodome>=3.18",
        "fastapi",
        "pydantic",
        "requests"
    ],
    extras_require={
        "captcha": ["ddddocr>=1.5.0"],
        "server": ["gunicorn>=20.1.0", "uvicorn>=0.15.0"],
    },
    classifiers=[
        "Programming Language :: Python :: 3",
        "Operating System :: OS Independent",
    ],
    python_requires='>=3.9',
)
