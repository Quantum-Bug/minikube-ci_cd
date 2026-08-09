import logging, sys, os
from flask import Flask, jsonify

logging.basicConfig(stream=sys.stdout, level=logging.INFO,
    format='%(asctime)s level=%(levelname)s service=python-backend msg="%(message)s"')
log = logging.getLogger(__name__)

app = Flask(__name__)

@app.route("/")
def home():
    log.info("root endpoint hit")
    return jsonify(service="python-backend", status="ok", version="main", version="demo")

@app.route("/health")
def health():
    return jsonify(status="healthy"), 200

if __name__ == "__main__":
    log.info("python-backend starting on port 5000")
    app.run(host="0.0.0.0", port=5000)
