import os
from pathlib import Path
import subprocess

import pytest

ENTRYPOINT = Path(__file__).resolve().parents[1] / 'docker-entrypoint.sh'


@pytest.fixture
def run_entrypoint(tmp_path):
    log = tmp_path / 'commands'
    for command in ('alembic', 'uvicorn'):
        stub = tmp_path / command
        stub.write_text('#!/bin/sh\nprintf "%s\\n" "' + command + ' $*" >> "$COMMAND_LOG"\n' +
                        ('exit "${MIGRATION_EXIT:-0}"\n' if command == 'alembic' else ''))
        stub.chmod(0o755)

    def run(value=None, command=('uvicorn', 'gradient.app:app'), migration_exit=0):
        env = {**os.environ, 'PATH': f'{tmp_path}:/usr/bin:/bin', 'COMMAND_LOG': str(log),
               'MIGRATION_EXIT': str(migration_exit)}
        env.pop('GRADIENT_AUTO_MIGRATE', None)
        if value is not None:
            env['GRADIENT_AUTO_MIGRATE'] = value
        result = subprocess.run(['sh', str(ENTRYPOINT), *command], env=env, capture_output=True, text=True)
        return result, log.read_text().splitlines() if log.exists() else []
    return run


@pytest.mark.parametrize('value', [None, '', 'false', '0', 'FALSE'])
def test_default_or_disabled_does_not_migrate(run_entrypoint, value):
    result, commands = run_entrypoint(value)
    assert result.returncode == 0
    assert commands == ['uvicorn gradient.app:app']


@pytest.mark.parametrize('value', ['true', '1', 'TRUE'])
def test_enabled_migrates_before_server(run_entrypoint, value):
    result, commands = run_entrypoint(value)
    assert result.returncode == 0
    assert commands == ['alembic upgrade head', 'uvicorn gradient.app:app']


def test_migration_failure_prevents_server_start(run_entrypoint):
    result, commands = run_entrypoint('true', migration_exit=7)
    assert result.returncode == 7
    assert commands == ['alembic upgrade head']


def test_invalid_configuration_prevents_server_start(run_entrypoint):
    result, commands = run_entrypoint('typo')
    assert result.returncode == 64
    assert 'GRADIENT_AUTO_MIGRATE' in result.stderr
    assert commands == []


def test_manual_migration_is_not_duplicated(run_entrypoint):
    result, commands = run_entrypoint('true', command=('alembic', 'upgrade', 'head'))
    assert result.returncode == 0
    assert commands == ['alembic upgrade head']
