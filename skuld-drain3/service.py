#!/usr/bin/env python3

import json
import logging
import os
import re
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from drain3 import TemplateMiner
from drain3.template_miner_config import TemplateMinerConfig


HTTP_METHODS = ("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS")
HTTP_STATUS_REASONS = (
    "Continue",
    "Switching Protocols",
    "Processing",
    "Early Hints",
    "OK",
    "Created",
    "Accepted",
    "Non-Authoritative Information",
    "No Content",
    "Reset Content",
    "Partial Content",
    "Multi-Status",
    "Already Reported",
    "IM Used",
    "Multiple Choices",
    "Moved Permanently",
    "Found",
    "See Other",
    "Not Modified",
    "Use Proxy",
    "Temporary Redirect",
    "Permanent Redirect",
    "Bad Request",
    "Unauthorized",
    "Payment Required",
    "Forbidden",
    "Not Found",
    "Method Not Allowed",
    "Not Acceptable",
    "Proxy Authentication Required",
    "Request Timeout",
    "Conflict",
    "Gone",
    "Length Required",
    "Precondition Failed",
    "Payload Too Large",
    "URI Too Long",
    "Unsupported Media Type",
    "Range Not Satisfiable",
    "Expectation Failed",
    "I'm a Teapot",
    "Misdirected Request",
    "Unprocessable Entity",
    "Locked",
    "Failed Dependency",
    "Too Early",
    "Upgrade Required",
    "Precondition Required",
    "Too Many Requests",
    "Request Header Fields Too Large",
    "Unavailable For Legal Reasons",
    "Internal Server Error",
    "Not Implemented",
    "Bad Gateway",
    "Service Unavailable",
    "Gateway Timeout",
    "HTTP Version Not Supported",
    "Variant Also Negotiates",
    "Insufficient Storage",
    "Loop Detected",
    "Not Extended",
    "Network Authentication Required",
)

UUID_PATTERN = re.compile(
    r"\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b"
)
EMAIL_PATTERN = re.compile(r"\b[\w.+-]+@[\w.-]+\.[A-Za-z]{2,}\b")
QUERY_PARAM_PATTERN = re.compile(r"([?&][^=\s&]+)=([^&\s]+)")
ID_LABEL_PATTERN = re.compile(r"(\b(?:ID|id|userId|requestId|traceId)\b:?\s+)(\S+)")
TIMEOUT_PATTERN = re.compile(r"(\b(?:connectTimeout|readTimeout|socketTimeout|timeout)=)(\d+)\b")
ASSIGNMENT_NUMBER_PATTERN = re.compile(r"(\b[a-zA-Z_][\w.-]*=)(-?\d+)\b")
PATH_DYNAMIC_SEGMENT_PATTERN = re.compile(r"(/)(?=[^/\s]*\d)[^/\s]{6,}")

UUID_TOKEN = "UUID_TOKEN"
EMAIL_TOKEN = "EMAIL_TOKEN"
ID_TOKEN = "ID_TOKEN"
NUMBER_TOKEN = "NUMBER_TOKEN"
VALUE_TOKEN = "VALUE_TOKEN"
HTTP_STATUS_TOKEN_PREFIX = "HTTP_STATUS_"


def protect_http_methods(log: str) -> str:
    for method in HTTP_METHODS:
        log = re.sub(rf"\b{method}\b", f"HTTP_METHOD_{method}", log)
    return log


def protect_http_statuses(log: str) -> str:
    for reason in sorted(HTTP_STATUS_REASONS, key=len, reverse=True):
        escaped_reason = re.escape(reason)
        pattern = rf"\b([1-5]\d{{2}})\s+{escaped_reason}\b"
        replacement_reason = reason.upper().replace(" ", "_").replace("-", "_").replace("'", "")
        log = re.sub(pattern, rf"{HTTP_STATUS_TOKEN_PREFIX}\1_{replacement_reason}", log)
    return log


def restore_http_methods(template: str) -> str:
    for method in HTTP_METHODS:
        template = template.replace(f"HTTP_METHOD_{method}", method)
    return template


def restore_http_statuses(template: str) -> str:
    pattern = re.compile(rf"{HTTP_STATUS_TOKEN_PREFIX}([1-5]\d{{2}})_([A-Z_]+)")

    def repl(match: re.Match) -> str:
        code = match.group(1)
        reason = match.group(2).replace("_", " ").title()
        if reason == "Im A Teapot":
            reason = "I'm a Teapot"
        return f"{code} {reason}"

    return pattern.sub(repl, template)


def normalize_dynamic_values(log: str) -> str:
    log = UUID_PATTERN.sub(UUID_TOKEN, log)
    log = EMAIL_PATTERN.sub(EMAIL_TOKEN, log)
    log = QUERY_PARAM_PATTERN.sub(r"\1=" + VALUE_TOKEN, log)
    log = ID_LABEL_PATTERN.sub(r"\1" + ID_TOKEN, log)
    log = TIMEOUT_PATTERN.sub(r"\1" + NUMBER_TOKEN, log)
    log = ASSIGNMENT_NUMBER_PATTERN.sub(r"\1" + NUMBER_TOKEN, log)
    log = PATH_DYNAMIC_SEGMENT_PATTERN.sub(r"/" + UUID_TOKEN, log)
    return log


def restore_dynamic_values(template: str) -> str:
    template = template.replace(UUID_TOKEN, "<*>")
    template = template.replace(EMAIL_TOKEN, "<*>")
    template = template.replace(ID_TOKEN, "<*>")
    template = template.replace(NUMBER_TOKEN, "<*>")
    return template.replace(VALUE_TOKEN, "<*>")


def restore_url_separators(template: str) -> str:
    match = re.search(r"\bURL:?\s+(.*)", template)
    if not match:
        return template

    url_part = match.group(1).strip()
    parts = url_part.split()
    if not parts:
        return template

    if len(parts) >= 2 and parts[0] in {"http", "https", "ftp"}:
        rebuilt = f"{parts[0]}://{parts[1]}"
        remaining = parts[2:]
    else:
        rebuilt = parts[0]
        remaining = parts[1:]

    for part in remaining:
        if part.startswith("?") or part.startswith("&"):
            rebuilt += part
            continue
        rebuilt += "/" + part

    prefix = template[: match.start()]
    return f"{prefix}URL: {rebuilt}"


def build_template_miner() -> TemplateMiner:
    config = TemplateMinerConfig()
    config.drain_sim_th = float(os.getenv("DRAIN3_SIM_TH", "0.75"))
    config.drain_extra_delimiters = [":", "/", "?", "=", "&"]

    config_path = Path(os.getenv("DRAIN3_CONFIG", "/app/drain3.ini"))
    if config_path.exists():
        config.load(str(config_path))

    return TemplateMiner(config=config)


template_miner = build_template_miner()


class Drain3Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def _write_json(self, status_code: int, payload: dict) -> None:
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status_code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self) -> None:
        if self.path != "/cluster":
            self._write_json(404, {"error": "not found"})
            return

        try:
            content_length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            self._write_json(400, {"error": "invalid content length"})
            return

        try:
            raw_body = self.rfile.read(content_length)
            payload = json.loads(raw_body.decode("utf-8"))
        except json.JSONDecodeError:
            self._write_json(400, {"error": "invalid json"})
            return

        messages = payload.get("messages")
        if not isinstance(messages, list) or any(not isinstance(msg, str) for msg in messages):
            self._write_json(400, {"error": "messages must be a list of strings"})
            return

        results = []
        for message in messages:
            normalized = normalize_dynamic_values(protect_http_statuses(protect_http_methods(message)))
            mined = template_miner.add_log_message(normalized)
            template = mined.get("template_mined", normalized)
            template = restore_http_statuses(
                restore_http_methods(restore_dynamic_values(restore_url_separators(template)))
            )
            cluster_id = str(mined.get("cluster_id", ""))
            results.append(
                {
                    "message": message,
                    "template": template,
                    "cluster_id": cluster_id,
                }
            )

        self._write_json(200, {"results": results})

    def do_GET(self) -> None:
        if self.path == "/health":
            self._write_json(200, {"status": "ok"})
            return
        self._write_json(404, {"error": "not found"})

    def log_message(self, format: str, *args) -> None:
        logging.info("%s - %s", self.address_string(), format % args)


def main() -> None:
    logging.basicConfig(level=os.getenv("LOG_LEVEL", "INFO").upper())
    host = os.getenv("DRAIN3_HOST", "0.0.0.0")
    port = int(os.getenv("DRAIN3_PORT", "8011"))
    server = ThreadingHTTPServer((host, port), Drain3Handler)
    logging.info("Drain3 service listening on http://%s:%s", host, port)
    server.serve_forever()


if __name__ == "__main__":
    main()
