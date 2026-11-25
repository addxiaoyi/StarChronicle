"""Normally stop the task-owned MySQL container after every associated Paper server stopped."""
from pathlib import Path
import argparse
import json
import os
import socket
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--instance', type=Path, required=True)
    args = parser.parse_args()
    target = json.loads(args.instance.read_text())
    assert target['container'] == 'starcore-paper-mysql-20261125' and target['local_only'] is True
    output = args.instance.parent / 'shutdown.json'
    assert not output.exists()
    for path in (ROOT / 'run').glob('*/preparation.json'):
        manifest = json.loads(path.read_text())
        if manifest.get('mysql_acceptance', {}).get('container') != target['container']:
            continue
        with socket.socket() as probe:
            probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            probe.bind(('127.0.0.1', manifest['server_port']))
        for session in (path.parent / 'evidence').glob('boot*.session.json'):
            data = json.loads(session.read_text())
            assert all(p.get('exit_code') == 0 for p in data['processes']), 'Investigate abnormal game exit before shutdown'
    env = {k: v for k, v in os.environ.items() if k not in {'DOCKER_HOST', 'DOCKER_CONTEXT', 'DOCKER_TLS', 'DOCKER_TLS_VERIFY', 'DOCKER_CERT_PATH'}}
    def docker(*arguments):
        return subprocess.check_output(['docker', '--host=unix:///var/run/docker.sock', *arguments], text=True, env=env).strip()
    assert docker('inspect', '--format', '{{.Id}}', target['container']) == target['id']
    assert docker('inspect', '--format', '{{index .Config.Labels "codex.task"}}', target['container']) == target['container']
    before = json.loads(docker('inspect', '--format', '{{json .State}}', target['container']))
    assert before['Running']
    began = time.time()
    docker('stop', '--time', '30', target['container'])
    after = json.loads(docker('inspect', '--format', '{{json .State}}', target['container']))
    assert not after['Running'] and after['ExitCode'] == 0 and not after['OOMKilled']
    with (args.instance.parent / 'mysql-server.log').open('x') as log:
        subprocess.run(['docker', '--host=unix:///var/run/docker.sock', 'logs', target['container']], stdout=log, stderr=subprocess.STDOUT, env=env, check=True)
    with output.open('x') as f:
        json.dump(dict(container=target['container'], container_id=target['id'], before=before, state=after, started_at=began, finished_at=time.time()), f, indent=2)
        f.write('\n')
    print(json.dumps(dict(status='NORMAL_STOPPED', container_id=target['id'], exit_code=after['ExitCode'])))


if __name__ == '__main__':
    main()
