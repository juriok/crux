FROM python:3.12-slim

WORKDIR /srv
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY app/ app/

# unbuffered so docker logs are live
ENV DATA_DIR=/data PYTHONUNBUFFERED=1
EXPOSE 8000
CMD ["uvicorn", "app.main:asgi", "--host", "0.0.0.0", "--port", "8000", "--proxy-headers", "--forwarded-allow-ips", "*"]
