# 使用轻量级 Python 基础镜像
FROM python:3.10-slim

ARG INSTALL_DDDDOCR=1

# 设置工作目录
WORKDIR /app

# 设置环境变量
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    AUTH_DB_PATH=/app/data/auth.db

# 复制依赖文件
COPY server/requirements.txt .

# 安装依赖
RUN if [ "$INSTALL_DDDDOCR" = "0" ]; then sed -i '/ddddocr/d' requirements.txt; fi && \
    pip install --no-cache-dir -r requirements.txt

# 复制项目源码
COPY bit_login/ bit_login/
COPY server/ server/
COPY setup.py README.md ./

RUN mkdir -p /app/data && chmod 700 /app/data

# 安装当前项目 (bit-login)
RUN pip install --no-cache-dir .

# 创建 server/__init__.py 以便 gunicorn 可以通过 server:app 导入
COPY server/__init__.py server/__init__.py

# 设置启动脚本权限
RUN chmod +x server/start.sh

# 暴露端口
EXPOSE 16384

# 启动服务
CMD ["./server/start.sh"]
