FROM python:3.13-slim

WORKDIR /app

# Install dependencies first for layer caching
COPY requirements.txt .
RUN pip install --no-cache-dir --upgrade pip && \
    pip install --no-cache-dir -r requirements.txt

# Copy application source
COPY *.py .
COPY templates/ templates/
COPY static/ static/

# Persistent data directory for config.json and threatlandscape.db
RUN mkdir -p /app/data

# Run as non-root user for security
RUN useradd --create-home appuser && chown -R appuser:appuser /app
USER appuser

ENV HOST=0.0.0.0
ENV PORT=5000
ENV DATA_DIR=/app/data

EXPOSE 5000

ENTRYPOINT ["python", "app.py"]
