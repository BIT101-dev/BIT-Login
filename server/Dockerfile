# 使用轻量级 Python 基础镜像
FROM python:3.14-slim

# 设置工作目录
WORKDIR /app

# 设置环境变量
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

# 复制依赖文件
COPY server/requirements.txt .

# 安装依赖
# 移除 requirements.txt 中的 bit-login，因为我们将使用本地源码安装
RUN sed -i '/bit-login/d' requirements.txt && \
    pip install --no-cache-dir -r requirements.txt

# 复制项目源码
COPY bit_login/ bit_login/
COPY server/ server/
COPY setup.py README.md ./

# 安装当前项目 (bit-login)
RUN pip install --no-cache-dir .

# 创建 server/__init__.py 以便 gunicorn 可以通过 server:app 导入
RUN echo "from .server import app" > server/__init__.py

# 设置启动脚本权限
RUN chmod +x server/start.sh

# 暴露端口
EXPOSE 16384

# 启动服务
CMD ["./server/start.sh"]
