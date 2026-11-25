"""Create a dedicated local MySQL8.4 container and five empty acceptance catalogs.

Requires the pinned image to be present locally. Credentials stay in a private
0600 env file outside Git; tool output contains only public instance metadata.
"""
from pathlib import Path
import argparse
import json
import os
import secrets
import socket
import subprocess
import time

IMAGE = 'public.ecr.aws/docker/library/mysql@sha256:8c233bad33d520a7aae1c1e619c3a9775f4a1ba2d463d692fada04ec7f40be00'
CONTAINER = 'starcore-paper-mysql-20261125'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', type=Path, required=True, help='New private directory outside the repository')
    parser.add_argument('--port', type=int, default=33317)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    directory = args.directory.resolve()
    assert not directory.is_relative_to(root) and not directory.exists()
    assert 1024 < args.port < 65536 and args.port not in (3306, 8000, 8001, 8080, 8081, 8082, 8083, 8085, 9222, 31460, 50002)
    with socket.socket() as probe:
        probe.bind(('127.0.0.1', args.port))
    env = {k: v for k, v in os.environ.items() if k not in {'DOCKER_HOST', 'DOCKER_CONTEXT', 'DOCKER_TLS', 'DOCKER_TLS_VERIFY', 'DOCKER_CERT_PATH'}}
    base = ['docker', '--host=unix:///var/run/docker.sock']
    def docker(*arguments, input=None):
        return subprocess.run([*base, *arguments], input=input, text=True, capture_output=True, env=env, check=True).stdout.strip()
    assert CONTAINER not in docker('ps', '-a', '--format', '{{.Names}}').splitlines(), 'Existing task container retained; do not reuse or remove it silently'
    docker('image', 'inspect', IMAGE)
    directory.mkdir(mode=0o700)
    credential = directory / 'mysql.env'
    root_password, password = secrets.token_hex(32), secrets.token_hex(32)
    with credential.open('x', opener=lambda path, flags: os.open(path, flags, 0o600)) as f:
        f.write(f'MYSQL_ROOT_PASSWORD={root_password}\nMYSQL_USER=starcore_paper\nMYSQL_PASSWORD={password}\nMYSQL_DATABASE=starcore_paper_production\n')
    identity = docker('run', '-d', '--name', CONTAINER, '--label', 'codex.task=' + CONTAINER,
                      '--publish', f'127.0.0.1:{args.port}:3306', '--memory', '768m', '--cpus', '1',
                      '--env-file', str(credential), IMAGE, '--innodb-buffer-pool-size=64M', '--max-connections=40')
    mysql = ['exec', '-i', CONTAINER, 'sh', '-c', 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --protocol=tcp -h127.0.0.1 -uroot --batch']
    deadline = time.monotonic() + 60
    while True:
        try:
            version = docker(*mysql, input='SELECT VERSION();\n')
            assert '8.4.' in version
            break
        except subprocess.CalledProcessError:
            if time.monotonic() >= deadline:
                raise RuntimeError('Dedicated MySQL startup failed; container and private directory retained') from None
            time.sleep(.25)
    databases = ['starcore_paper_' + name for name in ('production', 'offline', 'trade', 'dungeon', 'war')]
    sql = []
    for database in databases[1:]:
        escaped = database.replace('_', '\\_')
        sql += [f'CREATE DATABASE `{database}` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;', f"GRANT ALL PRIVILEGES ON `{escaped}`.* TO 'starcore_paper'@'%';"]
    (directory / 'catalog-setup.log').write_text(docker(*mysql, input='\n'.join(sql) + '\n'))
    manifest = dict(container=CONTAINER, id=identity, image=IMAGE, host='127.0.0.1', port=args.port,
                    user='starcore_paper', databases=databases, password_env='STARCORE_DB_PASSWORD', local_only=True)
    (directory / 'preparation.json').write_text(json.dumps(manifest, indent=2) + '\n')
    print(json.dumps(dict(status='PREPARED_NOT_ACCEPTED', manifest=str(directory / 'preparation.json'))))


if __name__ == '__main__':
    main()
