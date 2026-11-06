from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json, time
mode = 'healthy'
failed = 0
requests = 0
errors = 0
alerts = []

class Handler(BaseHTTPRequestHandler):

    def log_message(self, *args):
        pass

    def do_GET(self):
        global requests, errors
        if self.path == '/actuator/prometheus':
            if mode == 'metrics-down':
                self.send_response(503)
                self.end_headers()
                return
            if self.headers.get('Authorization') != 'Bearer smoke-only':
                self.send_response(401)
                self.end_headers()
                return
            requests += 100
            if mode == 'errors':
                errors += 30
            body = f'http_server_requests_seconds_count{{status="200"}} {requests - errors}\nhttp_server_requests_seconds_count{{status="500"}} {errors}\nfgc_batch_last_failure_timestamp{{job="smokeBatch"}} {failed}\n'
            code = 200
        elif self.path == '/events':
            body = json.dumps(alerts)
            code = 200
        else:
            body = json.dumps({'status': 'DOWN' if mode == 'db-down' else 'UP'})
            code = 503 if mode == 'db-down' else 200
        self.send_response(code)
        self.send_header('Content-Type', 'text/plain; version=0.0.4' if self.path == '/actuator/prometheus' else 'application/json')
        self.end_headers()
        self.wfile.write(body.encode())

    def do_POST(self):
        global mode, failed
        data = self.rfile.read(int(self.headers.get('Content-Length', 0)))
        if self.path == '/alerts':
            alerts.append({'at': time.time(), 'payload': json.loads(data)})
        else:
            mode = self.path.lstrip('/')
            if mode == 'batch-failed':
                failed = int(time.time())
            elif mode == 'healthy':
                failed = 0
        self.send_response(200)
        self.end_headers()
ThreadingHTTPServer(('0.0.0.0', 8080), Handler).serve_forever()
