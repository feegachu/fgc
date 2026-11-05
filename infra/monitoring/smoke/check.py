import base64, json, time, urllib.request
from pathlib import Path
BASE = 'http://127.0.0.1:18774'

def get(url):
    with urllib.request.urlopen(url, timeout=5) as r:
        return json.load(r)

def wait(fn, seconds=60):
    end = time.monotonic() + seconds
    while time.monotonic() < end:
        try:
            value = fn()
            if value:
                return value
        except (OSError, ValueError, IndexError):
            pass
        time.sleep(1)
    raise AssertionError('Timed out: ' + fn.__name__)

def fire(mode, name):
    start = time.time()
    urllib.request.urlopen(urllib.request.Request(BASE + '/' + mode, data=b'', method='POST'), timeout=5).close()

    def received():
        for e in get(BASE + '/events'):
            if e['at'] >= start and any((a['labels']['alertname'] == name and a['status'] == 'firing' for a in e['payload']['alerts'])):
                return e
    event = wait(received)
    delay = round(event['at'] - start, 2)
    assert delay < 60
    return {'alert': name, 'seconds': delay}
wait(lambda: get('http://127.0.0.1:19090/api/v1/query?query=up%7Bjob%3D%22fgc%22%7D')['data']['result'][0]['value'][1] == '1')
wait(lambda: get('http://127.0.0.1:13000/api/health')['database'] == 'ok')
credentials = base64.b64encode(b'admin:local-smoke-only-password').decode()
dashboard = wait(lambda: get(urllib.request.Request('http://127.0.0.1:13000/api/dashboards/uid/fgc-observability', headers={'Authorization': 'Basic ' + credentials})))
assert len(dashboard['dashboard']['panels']) == 12
assert all((p['datasource']['uid'] == 'fgc-prometheus' for p in dashboard['dashboard']['panels']))
time.sleep(15)
results = [fire('db-down', 'FgcHealthDown'), fire('batch-failed', 'FgcBatchFailed'), fire('errors', 'FgcHighErrorRate'), fire('metrics-down', 'FgcMetricsUnavailable')]
count = lambda: sum((a['labels']['alertname'] == 'FgcBatchFailed' and a['status'] == 'firing' for e in get(BASE + '/events') for a in e['payload']['alerts']))
before = count()
time.sleep(20)
assert count() == before == 1
(Path(__file__).resolve().parents[1] / 'smoke-results.json').write_text(json.dumps({'alerts': results, 'duplicates': 0, 'grafana': 'healthy'}, indent=2))
print(json.dumps(results))
print('PASS: alerts <60s, duplicate suppression, Grafana')
