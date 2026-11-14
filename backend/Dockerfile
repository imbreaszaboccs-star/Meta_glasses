FROM python:3.13-slim

# MPLCONFIGDIR: matplotlib needs a writable cache dir; the app user has no home.
# FOCUSLENS_DATA_DIR: pairing store (SQLite, hashes only) on a volume, see compose.yaml.
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 MPLBACKEND=Agg MPLCONFIGDIR=/tmp/matplotlib \
    FOCUSLENS_DATA_DIR=/data
WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY ai.py app.py formula.py remote.py ./
COPY glasses/ ./glasses/

RUN useradd -r -u 10001 app && mkdir -p /data && chown app /data
USER app

EXPOSE 8000
HEALTHCHECK --interval=30s --timeout=3s CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8000/health')"
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8000", "--proxy-headers", "--forwarded-allow-ips=*"]
